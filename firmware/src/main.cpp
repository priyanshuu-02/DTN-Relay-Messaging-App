// ============================================================================
//  DTN LoRa Hub — Heltec WiFi LoRa 32 V3 (ESP32-S3 + SX1262)   firmware v2
//  Role: WiFi SoftAP for many phones  +  LoRa hub-to-hub backbone.
//  Framework: Arduino / PlatformIO    Library: RadioLib 6.6.x, U8g2
//
//  Phone <-> hub protocol (framed TCP :9740, matches LoRaHubTransportAdapter.kt):
//     [1B type][2B length BE][payload]
//       HELLO    0x03  hub -> phone   payload = 4B HUB_ID (sent on connect)
//       REGISTER 0x01  phone -> hub   payload = 4B phone node id
//       BUNDLE   0x02  either way     payload = DTN bundle (32B header + data)
//       PING     0x04  phone -> hub   any payload; keeps an idle phone connected
//       ROSTER   0x05  hub -> phone   [1B n][n x 4B node id] phones registered here
//                                     (on every join/leave and every 30 s)
//  v3: writes to phones are queued + drained non-blocking (v2's availableForWrite()
//  guard never passed on this core, so phones never received anything); broadcast
//  and receipts reach every phone here except the sender; ROSTER; PING.
//  Self-contained 32-byte DTN header (see DtnWireCodec.kt):
//       0:16 msgId  16:4 origin  20:4 dest  24:2 ttlMin  26:1 hop
//       27:1 msgType  28:1 channel  29:1 fragIdx  30:1 fragTotal  31:1 flags
//     msgType: 0x00 DATA, 0x01 ROUTING_SUMMARY, 0x02 ROUTING_ACK, 0x05 ANNOUNCE
//
//  v2 changes (vs the original flood firmware):
//   * Heltec V3 correct radio: 1.8V TCXO (+XTAL fallback), 22 dBm, 140 mA OCP,
//     boosted RX, preamble 16, explicit SPI pins, DIO2 RF switch.
//   * Region config (IN865 / EU868 / US915) in NVS; change over serial.
//   * HUB_ID + SoftAP SSID suffix derived from the eFuse MAC (identical binary).
//   * Interrupt RX/TX/CAD state machine (no more treating TX-done as RX).
//   * Listen-before-talk (CAD) + random jitter + overhear-suppression.
//   * Rolling 1-hour airtime ledger enforcing the region duty cycle.
//   * TTL aged by buffer residence before every re-flood (no immortal bundles).
//   * Non-blocking per-client TCP reader (a slow phone can't freeze the hub).
//   * Store-and-forward: buffered bundles replayed to a phone when it REGISTERs.
//   * ROUTING_ACK delivered to local phones (receipts cross hubs now).
//   * OLED status screen, battery readout, watchdog.
//  LoRa wire bytes are still EXACTLY the DTN bundle — no link header — so the
//  255-byte frame budget and dedup are unchanged and phones need no changes.
// ============================================================================
#include <WiFi.h>
#include <RadioLib.h>
#include <Preferences.h>
#include <U8g2lib.h>
#include <Wire.h>
#include <string.h>
#include <math.h>
#include "esp_task_wdt.h"
#include <lwip/sockets.h>
#include <errno.h>
extern "C" uint32_t esp_random(void);

// ---- Heltec WiFi LoRa 32 V3 pin map ---------------------------------------
static const int PIN_LORA_NSS = 8, PIN_LORA_DIO1 = 14, PIN_LORA_RST = 12, PIN_LORA_BUSY = 13;
static const int PIN_LORA_SCK = 9, PIN_LORA_MISO = 11, PIN_LORA_MOSI = 10;
static const int PIN_OLED_SDA = 17, PIN_OLED_SCL = 18, PIN_OLED_RST = 21;
static const int PIN_VEXT = 36;            // LOW = peripheral power on (OLED)
static const int PIN_VBAT_ADC = 1;         // battery divider
static const int PIN_VBAT_CTRL = 37;       // LOW enables the divider (V3.0/3.1)
static const float TCXO_VOLTAGE = 1.8f;    // Heltec V3 TCXO
static const uint8_t NET_SYNC   = 0x2B;    // private DTN sync word
static const uint16_t PREAMBLE  = 16;

// ---- Region profiles -------------------------------------------------------
struct RegionCfg { const char* name; float freq; float bw; uint8_t sf; uint8_t cr; int8_t power; uint8_t dutyPct; };
static const RegionCfg REGIONS[] = {
    { "IN865", 866.300f, 125.0f, 9, 5, 22, 10 },  // India (865-867 MHz), self-capped 10%
    { "EU868", 869.525f, 125.0f, 9, 5, 16, 10 },  // EU 869.4-869.65 sub-band, 10%
    { "US915", 906.875f, 500.0f, 9, 5, 22, 100 }, // FCC: >=500 kHz BW, dwell < 400 ms
};
static const int NUM_REGIONS = sizeof(REGIONS) / sizeof(REGIONS[0]);
int regionIdx = DTN_DEFAULT_REGION;
#define CFG (REGIONS[regionIdx])

// ---- Identity (derived from the eFuse MAC at boot) -------------------------
uint32_t HUB_ID = 0xA0000001;
char     AP_SSID[20] = "DTN-HUB-0000";
static const char* AP_PASS = "dtnmesh123";   // must match app HUB_PASSPHRASE (>= 8 chars)
static const uint8_t MAX_CLIENTS = 8;

// ---- Timing / limits -------------------------------------------------------
static const uint32_t DASHBOARD_INTERVAL_MS = 10000;
static const uint32_t HEARTBEAT_BASE_MS     = 60000;   // + up to 20% jitter
static const uint32_t NODE_TIMEOUT_MS       = 180000;  // 3 missed heartbeats
static const uint32_t OLED_INTERVAL_MS      = 1000;
static const uint32_t CLIENT_IDLE_MS        = 120000;  // drop silent phones
static const uint32_t SLOT_MS               = 20;      // ~5 symbols @ SF9/BW125
static const uint8_t  HOP_LIMIT             = 8;        // shared ceiling with phones
static const uint32_t WDT_TIMEOUT_S         = 30;

// ---- Server + connected phones --------------------------------------------
// Outgoing bytes per phone are queued and drained with non-blocking send(), so a
// stalled phone can never freeze the hub. (v2 gated writes on availableForWrite(),
// which this ESP32 core never implements — it always returned 0, so the hub never
// sent a single byte to any phone: no HELLO, no delivery, no replay.)
static const int CLIENT_TXQ = 4096;
WiFiServer tcpServer(9740);
struct DtnClient {
    WiFiClient sock;
    uint32_t   nodeId;
    bool       used;
    bool       greeted;
    // The app PINGs every 30 s (v3 apps). Only such phones are dropped for being silent:
    // older apps never PING and don't notice a drop, so they'd sit "connected" but unseen.
    bool       pings;
    uint32_t   lastSeenMs;
    // non-blocking frame reader state
    uint8_t    hdr[3];
    int        hdrGot;
    uint8_t    rxType;
    uint16_t   rxLen;
    int        rxGot;
    uint8_t    rxBuf[256];
    // non-blocking writer state
    uint8_t    txq[CLIENT_TXQ];
    int        txLen;
    // store-and-forward replay after REGISTER (resumes as the queue drains)
    int        replayIdx;   // -1 = idle
    int        replayed;
};
DtnClient clients[MAX_CLIENTS];

// PING (phone -> hub) only keeps the link alive. ROSTER (hub -> phone) lists the
// phones registered here, so phones on the same hub know they can reach each other.
enum : uint8_t { FRAME_REGISTER = 0x01, FRAME_BUNDLE = 0x02, FRAME_HELLO = 0x03, FRAME_PING = 0x04, FRAME_ROSTER = 0x05 };
static const uint32_t ROSTER_INTERVAL_MS = 30000;
bool     rosterDirty = false;
uint32_t nextRosterMs = 0;

// ---- DTN header offsets & message types ------------------------------------
static const int     HDR_MIN      = 32;
static const int     OFF_MSGID    = 0;
static const int     OFF_ORIGIN   = 16;
static const int     OFF_DEST     = 20;
static const int     OFF_TTL_MIN  = 24;
static const int     OFF_HOP      = 26;
static const int     OFF_MSGTYPE  = 27;
static const uint8_t MSGTYPE_DATA            = 0x00;
static const uint8_t MSGTYPE_ROUTING_SUMMARY = 0x01;
static const uint8_t MSGTYPE_ROUTING_ACK     = 0x02;
static const uint8_t MSGTYPE_ANNOUNCE        = 0x05;
static const uint32_t BROADCAST_DEST = 0xFFFFFFFFu;

// ---- Dedup ring ------------------------------------------------------------
static const int DEDUP_N = 256;
uint8_t dedupKey[DEDUP_N][16];
int     dedupHead = 0;
uint8_t ackDedupKey[DEDUP_N][16];
int     ackDedupHead = 0;

// ---- Buffer (single source of truth for store/forward + TX) ----------------
struct BufferEntry {
    bool     valid;
    uint8_t  data[256];
    uint16_t len;
    uint8_t  msgId[16];
    uint32_t origin;
    uint32_t dest;
    uint16_t ttlMin;         // TTL as observed at insert
    uint8_t  hopCount;       // current hop (written into data on TX)
    uint8_t  msgType;
    uint32_t bufferedAtMs;
    bool     waitingForAck;
    bool     txPending;      // queued for a LoRa (re)transmission
    uint32_t txNotBeforeMs;  // jitter / backoff gate
    uint8_t  txTries;
};
static const int BUFFER_CAPACITY = 64;
BufferEntry buffer[BUFFER_CAPACITY];
uint32_t peakBufferUsage = 0;

// ---- Known peers (other hubs) ----------------------------------------------
struct KnownPeer { bool valid; uint32_t nodeId; int lastRssi; uint32_t lastSeenMs; bool active; };
static const int MAX_KNOWN_PEERS = 16;
KnownPeer knownPeers[MAX_KNOWN_PEERS];

// ---- Stats -----------------------------------------------------------------
struct Stats {
    uint32_t msgsGenerated, msgsForwarded, msgsDelivered, acksReceived;
    uint32_t duplicatesIgnored, duplicateAcksIgnored, ttlExpired, hopDropped;
    uint32_t bufferedNow, bufferInserts, bufferEvictions, suppressed, cadBusy, dutyBlocked;
    uint32_t fromPhones, toPhones, txDropped;
};
Stats stats = {};

// ---- Airtime ledger (rolling hour, per-minute buckets) ---------------------
uint32_t airMin[60] = {0};
int      airIdx = 0;
uint32_t lastAirMinute = 0;

// ---- Radio -----------------------------------------------------------------
SPIClass loraSpi(FSPI);
SX1262 radio = new Module(PIN_LORA_NSS, PIN_LORA_DIO1, PIN_LORA_RST, PIN_LORA_BUSY, loraSpi);
enum RadioState { RS_RX, RS_TX };
volatile bool radioIrqFlag = false;
RadioState radioState = RS_RX;
uint32_t   lastToaUs = 0;
void IRAM_ATTR onLoRaIrq() { radioIrqFlag = true; }

// ---- OLED ------------------------------------------------------------------
U8G2_SSD1306_128X64_NONAME_F_HW_I2C u8g2(U8G2_R0, PIN_OLED_RST, PIN_OLED_SCL, PIN_OLED_SDA);
bool oledOk = false;

// ---- Config store ----------------------------------------------------------
Preferences prefs;

// ---- Schedules -------------------------------------------------------------
uint32_t nextDashboardMs = 0, nextHeartbeatMs = 0, nextOledMs = 0;

// ---- forward declarations --------------------------------------------------
bool enqueueFrame(DtnClient& c, uint8_t type, const uint8_t* payload, uint16_t len);
bool deliverLocal(uint32_t dest, const uint8_t* data, uint16_t len, int skipClient);
void processInboundAck(const uint8_t* data, uint16_t len);
void routeBundle(const uint8_t* data, uint16_t len, bool fromLoRa, int rssi, int fromClient);
int  countActivePeers(); int countInactivePeers(); int countConnectedPhones(); int countWaitingMessages();
float readBatteryVolts();

// ============================================================================
//  Byte helpers
// ============================================================================
uint32_t rd32(const uint8_t* p) { return ((uint32_t)p[0]<<24)|((uint32_t)p[1]<<16)|((uint32_t)p[2]<<8)|p[3]; }
uint16_t rd16(const uint8_t* p) { return ((uint16_t)p[0]<<8)|p[1]; }
void wr32(uint8_t* p, uint32_t v){ p[0]=v>>24; p[1]=v>>16; p[2]=v>>8; p[3]=v; }
void wr16(uint8_t* p, uint16_t v){ p[0]=v>>8; p[1]=v&0xFF; }

void msgIdShort(const uint8_t* id, char out[9]) {
    static const char hx[] = "0123456789abcdef";
    for (int i=0;i<4;i++){ out[i*2]=hx[(id[i]>>4)&0xF]; out[i*2+1]=hx[id[i]&0xF]; }
    out[8]=0;
}
void formatNodeId(uint32_t v, char out[10]) {
    static const char hx[] = "0123456789abcdef";
    out[0]='!';
    for (int i=0;i<8;i++) out[1+i]=hx[(v>>((7-i)*4))&0xF];
    out[9]=0;
}

// ============================================================================
//  Dedup rings
// ============================================================================
bool seenBefore(const uint8_t* id) {
    for (int i=0;i<DEDUP_N;i++) if (memcmp(dedupKey[i],id,16)==0) return true;
    memcpy(dedupKey[dedupHead],id,16); dedupHead=(dedupHead+1)%DEDUP_N; return false;
}
void registerSeen(const uint8_t* id){ if(!seenBefore(id)){} }
bool ackSeenBefore(const uint8_t* id) {
    for (int i=0;i<DEDUP_N;i++) if (memcmp(ackDedupKey[i],id,16)==0) return true;
    memcpy(ackDedupKey[ackDedupHead],id,16); ackDedupHead=(ackDedupHead+1)%DEDUP_N; return false;
}

// ============================================================================
//  Buffer management
// ============================================================================
int findInBuffer(const uint8_t* msgId) {
    for (int i=0;i<BUFFER_CAPACITY;i++) if (buffer[i].valid && memcmp(buffer[i].msgId,msgId,16)==0) return i;
    return -1;
}
int findFreeSlot() { for (int i=0;i<BUFFER_CAPACITY;i++) if(!buffer[i].valid) return i; return -1; }
int evictOldest() {
    int oldest=-1; uint32_t oldestAt=0xFFFFFFFF;
    for (int i=0;i<BUFFER_CAPACITY;i++) if (buffer[i].valid && buffer[i].bufferedAtMs<oldestAt){ oldest=i; oldestAt=buffer[i].bufferedAtMs; }
    if (oldest>=0){ buffer[oldest].valid=false; stats.bufferEvictions++; if(stats.bufferedNow) stats.bufferedNow--; }
    return oldest;
}
void updatePeak(){ if (stats.bufferedNow>peakBufferUsage) peakBufferUsage=stats.bufferedNow; }

int bufferInsert(const uint8_t* data, uint16_t len) {
    if (len<HDR_MIN || len>255) return -1;
    int exist=findInBuffer(data+OFF_MSGID); if (exist>=0) return -1;
    int slot=findFreeSlot(); if (slot<0) slot=evictOldest(); if (slot<0) return -1;
    BufferEntry& e=buffer[slot];
    memcpy(e.data,data,len); e.len=len;
    memcpy(e.msgId,data+OFF_MSGID,16);
    e.origin=rd32(data+OFF_ORIGIN); e.dest=rd32(data+OFF_DEST);
    e.ttlMin=rd16(data+OFF_TTL_MIN); e.hopCount=data[OFF_HOP]; e.msgType=data[OFF_MSGTYPE];
    e.bufferedAtMs=millis();
    e.waitingForAck=(e.msgType==MSGTYPE_DATA && e.dest!=BROADCAST_DEST);
    e.txPending=false; e.txNotBeforeMs=0; e.txTries=0; e.valid=true;
    stats.bufferedNow++; stats.bufferInserts++; updatePeak();
    return slot;
}
// Aged remaining TTL (minutes) given buffer residence; <=0 means expired.
int agedTtlMin(const BufferEntry& e){
    uint32_t elapsedMin=(millis()-e.bufferedAtMs)/60000UL;
    return (int)e.ttlMin - (int)elapsedMin;
}
void bufferSweepExpired() {
    for (int i=0;i<BUFFER_CAPACITY;i++) {
        if (!buffer[i].valid) continue;
        if (agedTtlMin(buffer[i])<=0) {
            char idStr[9]; msgIdShort(buffer[i].msgId,idStr);
            Serial.printf("[LC] Expired         msg=%s hop=%u\n", idStr, buffer[i].hopCount);
            buffer[i].valid=false; if(stats.bufferedNow) stats.bufferedNow--;
            stats.ttlExpired++; stats.bufferEvictions++;
        }
    }
}

// ============================================================================
//  Peer tracking
// ============================================================================
void touchPeer(uint32_t nodeId, int rssi) {
    if (nodeId==HUB_ID || nodeId==0 || nodeId==BROADCAST_DEST) return;
    for (int i=0;i<MAX_KNOWN_PEERS;i++) if (knownPeers[i].valid && knownPeers[i].nodeId==nodeId){
        knownPeers[i].lastRssi=rssi; knownPeers[i].lastSeenMs=millis(); knownPeers[i].active=true; return;
    }
    int slot=-1;
    for (int i=0;i<MAX_KNOWN_PEERS;i++) if(!knownPeers[i].valid){ slot=i; break; }
    if (slot<0){ uint32_t oldestAt=0xFFFFFFFF; int oldest=0;
        for (int i=0;i<MAX_KNOWN_PEERS;i++) if (knownPeers[i].lastSeenMs<oldestAt){ oldestAt=knownPeers[i].lastSeenMs; oldest=i; }
        slot=oldest; }
    knownPeers[slot]={true,nodeId,rssi,millis(),true};
}
void refreshPeerActivity(){ uint32_t now=millis();
    for (int i=0;i<MAX_KNOWN_PEERS;i++) if (knownPeers[i].valid) knownPeers[i].active=(now-knownPeers[i].lastSeenMs)<NODE_TIMEOUT_MS; }
double estimateDistanceM(int rssi){
    if (rssi==0) return -1.0; int c=rssi; if(c<-120)c=-120; if(c>-20)c=-20;
    double d=pow(10.0,((-40.0)-(double)c)/30.0);
    if(!isfinite(d))return -1.0; if(d<0.5)d=0.5; if(d>5000.0)d=5000.0; return d;
}

// ============================================================================
//  Airtime ledger
// ============================================================================
void rollAirtime(){
    uint32_t m=millis()/60000UL;
    if (m!=lastAirMinute){
        uint32_t steps=m-lastAirMinute; if(steps>60)steps=60;
        for (uint32_t k=0;k<steps;k++){ airIdx=(airIdx+1)%60; airMin[airIdx]=0; }
        lastAirMinute=m;
    }
}
uint64_t airtimeSumUs(){ uint64_t s=0; for(int i=0;i<60;i++) s+=airMin[i]; return s; }
bool airtimeAllows(uint32_t toaUs){
    if (CFG.dutyPct>=100) return true;
    rollAirtime();
    uint64_t budget=(uint64_t)CFG.dutyPct*36000000ULL; // dutyPct% of 3600 s, in us
    return airtimeSumUs()+toaUs <= budget;
}
void airtimeCommit(uint32_t toaUs){ rollAirtime(); airMin[airIdx]+=toaUs; }
float airtimePct(){ uint64_t b=(uint64_t)3600000000ULL; return (float)((double)airtimeSumUs()*100.0/(double)b); }

// ============================================================================
//  Radio service (interrupt RX/TX/CAD state machine)
// ============================================================================
void startTxEntry(int idx){
    BufferEntry& e=buffer[idx];
    e.data[OFF_HOP]=e.hopCount;
    int ttl=agedTtlMin(e); if (ttl<0) ttl=0; wr16(e.data+OFF_TTL_MIN,(uint16_t)ttl);
    lastToaUs=radio.getTimeOnAir(e.len);
    radioIrqFlag=false; radioState=RS_TX;
    int st=radio.startTransmit(e.data,e.len);
    if (st!=RADIOLIB_ERR_NONE){ radioState=RS_RX; radio.startReceive(); e.txTries++; e.txNotBeforeMs=millis()+200; return; }
    e.txPending=false; stats.msgsForwarded++;
    airtimeCommit(lastToaUs);
}
void serviceRadio(){
    if (!radioIrqFlag) return;
    radioIrqFlag=false;
    if (radioState==RS_TX){
        radio.finishTransmit(); radioState=RS_RX; radio.startReceive(); return;
    }
    // RX done
    int len=radio.getPacketLength();
    if (len>0 && len<=255){
        uint8_t buf[256];
        int st=radio.readData(buf,len);
        int rssi=(int)radio.getRSSI();
        radio.startReceive();           // re-arm before processing
        if (st==RADIOLIB_ERR_NONE) routeBundle(buf,(uint16_t)len,true,rssi,-1);
    } else {
        radio.startReceive();
    }
}
void pumpLoRaTx(){
    if (radioState!=RS_RX) return;
    uint32_t now=millis();
    int idx=-1; uint32_t oldest=0xFFFFFFFF;
    for (int i=0;i<BUFFER_CAPACITY;i++){
        BufferEntry& e=buffer[i];
        if (e.valid && e.txPending && now>=e.txNotBeforeMs && e.bufferedAtMs<oldest){ idx=i; oldest=e.bufferedAtMs; }
    }
    if (idx<0) return;
    BufferEntry& e=buffer[idx];
    if (agedTtlMin(e)<=0){ e.txPending=false; return; }
    if (e.hopCount>=HOP_LIMIT){ e.txPending=false; stats.hopDropped++; return; }
    uint32_t toa=radio.getTimeOnAir(e.len);
    if (!airtimeAllows(toa)){ e.txNotBeforeMs=now+3000; stats.dutyBlocked++; return; }
    int cad=radio.scanChannel(); radioIrqFlag=false;
    if (cad==RADIOLIB_LORA_DETECTED){ radio.startReceive(); e.txNotBeforeMs=now+random(SLOT_MS,8*SLOT_MS); stats.cadBusy++; return; }
    startTxEntry(idx);
}

// ============================================================================
//  Framing to phones (queued, drained non-blocking)
// ============================================================================
// Queue a whole frame or nothing, so a full queue can never leave a half frame
// on the wire. A phone that stops reading fills its queue; its frames are then
// dropped (counted) and it is eventually timed out.
bool enqueueFrame(DtnClient& c, uint8_t type, const uint8_t* payload, uint16_t len) {
    if (!c.used || !c.sock.connected()) return false;
    if (CLIENT_TXQ - c.txLen < (int)(3+len)) { stats.txDropped++; return false; }
    uint8_t* p=c.txq+c.txLen;
    p[0]=type; p[1]=(uint8_t)(len>>8); p[2]=(uint8_t)(len&0xFF);
    if (len) memcpy(p+3,payload,len);
    c.txLen+=3+len;
    return true;
}
// Write as much of the queue as the socket accepts right now (never blocks).
void flushClient(DtnClient& c) {
    if (!c.used || c.txLen<=0) return;
    int fd=c.sock.fd(); if (fd<0) return;
    int n=send(fd,c.txq,c.txLen,MSG_DONTWAIT);
    if (n>0){
        if (n<c.txLen) memmove(c.txq,c.txq+n,c.txLen-n);
        c.txLen-=n;
    } else if (n<0 && errno!=EAGAIN && errno!=EWOULDBLOCK){
        c.sock.stop(); // broken link; acceptAndPoll reaps the slot
    }
}
// Deliver to registered phones: one destination, or every phone for a broadcast.
// skipClient = the phone that handed the bundle in (never echoed back to it).
bool deliverLocal(uint32_t dest, const uint8_t* data, uint16_t len, int skipClient) {
    bool any=false;
    for (int i=0;i<MAX_CLIENTS;i++){
        if (i==skipClient) continue;
        DtnClient& c=clients[i];
        if (!c.used || !c.nodeId || !c.sock.connected()) continue;
        if (dest==BROADCAST_DEST || c.nodeId==dest){
            if (enqueueFrame(c,FRAME_BUNDLE,data,len)){ any=true; stats.toPhones++; }
            if (dest!=BROADCAST_DEST) break;
        }
    }
    return any;
}
// Replay what a freshly registered phone should have (store & forward): bundles
// for it, broadcasts and receipts — never its own bundles. Each copy carries the
// TTL aged by its time in our buffer (TTL only ever counts down). Resumes on the
// next loop pass whenever the phone's queue is full.
void pumpReplay(DtnClient& c){
    if (c.replayIdx<0) return;
    while (c.replayIdx<BUFFER_CAPACITY){
        BufferEntry& e=buffer[c.replayIdx];
        int ttl=e.valid ? agedTtlMin(e) : 0;
        if (e.valid && ttl>0 && e.origin!=c.nodeId &&
            (e.dest==c.nodeId || e.dest==BROADCAST_DEST || e.msgType==MSGTYPE_ROUTING_ACK)){
            if (CLIENT_TXQ - c.txLen < (int)(3+e.len)) return; // resume when it drains
            uint8_t copy[256]; memcpy(copy,e.data,e.len);
            copy[OFF_HOP]=e.hopCount; wr16(copy+OFF_TTL_MIN,(uint16_t)ttl);
            if (enqueueFrame(c,FRAME_BUNDLE,copy,e.len)) c.replayed++;
        }
        c.replayIdx++;
    }
    if (c.replayed){ char n[10]; formatNodeId(c.nodeId,n); Serial.printf("[NET] Replayed %d buffered bundle(s) to %s\n", c.replayed, n); }
    c.replayIdx=-1; c.replayed=0;
}
// ROSTER: [1B count][count x 4B node id] of the phones registered at this hub.
void broadcastRoster(){
    uint8_t p[1+MAX_CLIENTS*4]; uint8_t n=0;
    for (int i=0;i<MAX_CLIENTS;i++){
        DtnClient& c=clients[i];
        if (c.used && c.nodeId && c.sock.connected()){ wr32(p+1+n*4,c.nodeId); n++; }
    }
    p[0]=n;
    for (int i=0;i<MAX_CLIENTS;i++) if (clients[i].used && clients[i].nodeId) enqueueFrame(clients[i],FRAME_ROSTER,p,1+n*4);
}
void maybeSendRoster(){
    uint32_t now=millis();
    if (!rosterDirty && (int32_t)(now-nextRosterMs)<0) return;
    rosterDirty=false; nextRosterMs=now+ROSTER_INTERVAL_MS;
    broadcastRoster();
}

// ============================================================================
//  ACK handling
// ============================================================================
void processInboundAck(const uint8_t* data, uint16_t len) {
    if (len<HDR_MIN) return;
    const uint8_t* payload=data+HDR_MIN; uint16_t plen=len-HDR_MIN;
    int count=plen/16; if (!count) return;
    stats.acksReceived++;
    for (int i=0;i<count;i++){
        const uint8_t* mid=payload+i*16;
        if (ackSeenBefore(mid)){ stats.duplicateAcksIgnored++; continue; }
        int bufIdx=findInBuffer(mid);
        if (bufIdx>=0){
            char idStr[9]; msgIdShort(mid,idStr);
            Serial.printf("[LC] AckCleared      msg=%s\n", idStr);
            buffer[bufIdx].valid=false; if(stats.bufferedNow) stats.bufferedNow--;
        }
    }
}

// ============================================================================
//  Central routing decision
// ============================================================================
void queueForFlood(const uint8_t* data, uint16_t len, uint8_t newHop){
    int idx=findInBuffer(data+OFF_MSGID);
    if (idx<0) idx=bufferInsert(data,len);
    if (idx<0) return;
    buffer[idx].hopCount=newHop;
    if (newHop>=HOP_LIMIT){ stats.hopDropped++; return; }
    buffer[idx].txPending=true;
    buffer[idx].txNotBeforeMs=millis()+random(SLOT_MS,8*SLOT_MS);
}
// fromClient = index of the phone that handed the bundle in, or -1 for LoRa.
void routeBundle(const uint8_t* data, uint16_t len, bool fromLoRa, int rssi, int fromClient) {
    if (len<HDR_MIN || len>255) return;
    uint32_t origin=rd32(data+OFF_ORIGIN), dst=rd32(data+OFF_DEST);
    uint8_t  hop=data[OFF_HOP], mt=data[OFF_MSGTYPE];
    uint16_t ttl=rd16(data+OFF_TTL_MIN);
    if (fromLoRa) touchPeer(origin,rssi);

    // Overhear-suppression: another node relayed a bundle we had queued — cancel ours.
    if (fromLoRa){ int qi=findInBuffer(data+OFF_MSGID); if (qi>=0 && buffer[qi].txPending){ buffer[qi].txPending=false; stats.suppressed++; } }

    if (fromLoRa && seenBefore(data+OFF_MSGID)){ stats.duplicatesIgnored++; return; }

    // Routing summaries: 1-hop neighbour state. Learn the peer, never buffer/flood.
    if (mt==MSGTYPE_ROUTING_SUMMARY) return;

    if (ttl==0){ stats.ttlExpired++; return; }

    bool isBroadcast=(dst==BROADCAST_DEST);
    bool forHub=(dst==HUB_ID);
    bool isAck=(mt==MSGTYPE_ROUTING_ACK);
    // A phone handing us a bundle we already hold (it retries until the receipt):
    // a DM is delivered locally again (its phone may be here now) but never
    // re-flooded; broadcasts/receipts already reached everyone here (on arrival
    // or by replay), so they are ignored.
    bool alreadyHeld = !fromLoRa && findInBuffer(data+OFF_MSGID)>=0;
    if (alreadyHeld && (isBroadcast || isAck)){ stats.duplicatesIgnored++; return; }

    bool delivered=false;
    if (isAck){
        processInboundAck(data,len);
        // Receipts are tiny and every carrier may hold the acked bundle: give them
        // to every phone here, whoever they're addressed to (often this hub).
        deliverLocal(BROADCAST_DEST,data,len,fromClient);
    } else {
        delivered=deliverLocal(dst,data,len,fromClient);
        if (delivered && !isBroadcast) stats.msgsDelivered++;
    }

    if (forHub && !isAck) return;                 // consumed by this hub

    if (!fromLoRa) registerSeen(data+OFF_MSGID);  // so our echo is later ignored
    if (alreadyHeld) return;

    bool shouldFlood = isBroadcast || !delivered || isAck;
    if (shouldFlood){
        uint8_t newHop = fromLoRa ? (uint8_t)(hop+1) : hop;
        queueForFlood(data,len,newHop);
    }
}

// ============================================================================
//  Heartbeat (empty ROUTING_SUMMARY, origin = HUB_ID)
// ============================================================================
void buildHeartbeat(uint8_t* hdr){
    wr32(hdr+OFF_MSGID,HUB_ID);
    for (int i=4;i<16;i++) hdr[OFF_MSGID+i]=(uint8_t)(esp_random()&0xFF);
    wr32(hdr+OFF_ORIGIN,HUB_ID); wr32(hdr+OFF_DEST,BROADCAST_DEST);
    wr16(hdr+OFF_TTL_MIN,5); hdr[OFF_HOP]=0; hdr[OFF_MSGTYPE]=MSGTYPE_ROUTING_SUMMARY;
    hdr[28]=0; hdr[29]=0; hdr[30]=1; hdr[31]=0;
}
void maybeSendHeartbeat(){
    uint32_t now=millis();
    if ((int32_t)(now-nextHeartbeatMs)<0) return;
    nextHeartbeatMs=now+HEARTBEAT_BASE_MS+random(0,HEARTBEAT_BASE_MS/5);
    if (radioState!=RS_RX) { nextHeartbeatMs=now+2000; return; }
    uint8_t hb[HDR_MIN]; buildHeartbeat(hb);
    uint32_t toa=radio.getTimeOnAir(HDR_MIN);
    if (!airtimeAllows(toa)) return;
    radioIrqFlag=false; radioState=RS_TX;
    if (radio.startTransmit(hb,HDR_MIN)==RADIOLIB_ERR_NONE){ stats.msgsGenerated++; seenBefore(hb+OFF_MSGID); airtimeCommit(toa); }
    else { radioState=RS_RX; radio.startReceive(); }
}

// ============================================================================
//  TCP <-> phones (non-blocking)
// ============================================================================
void dropClient(DtnClient& c, const char* why){
    if (c.nodeId){ char n[10]; formatNodeId(c.nodeId,n); Serial.printf("[NET] Phone %s node=%s\n",why,n); rosterDirty=true; }
    c.sock.stop(); c.used=false; c.nodeId=0; c.txLen=0; c.replayIdx=-1;
}
void dispatchClientFrame(DtnClient& c, int idx){
    if (c.rxType==FRAME_REGISTER && c.rxLen>=4){
        c.nodeId=rd32(c.rxBuf);
        // A reconnecting phone: retire its old, not-yet-noticed dead connection, so
        // deliveries never go to the stale socket and the roster lists it once.
        for (int i=0;i<MAX_CLIENTS;i++) if (i!=idx && clients[i].used && clients[i].nodeId==c.nodeId) dropClient(clients[i],"replaced");
        char n[10]; formatNodeId(c.nodeId,n);
        Serial.printf("[NET] Phone registered node=%s\n", n);
        rosterDirty=true;
        c.replayIdx=0; c.replayed=0;
    } else if (c.rxType==FRAME_BUNDLE){
        stats.fromPhones++;
        routeBundle(c.rxBuf,c.rxLen,false,0,idx);
    } else if (c.rxType==FRAME_PING){
        c.pings=true;
    }
    // Anything unknown: activity only — lastSeenMs is already updated.
}
void readClient(DtnClient& c, int idx){
    while (c.sock.connected() && c.sock.available()>0){
        if (c.hdrGot<3){
            int want=3-c.hdrGot;
            int got=c.sock.read(c.hdr+c.hdrGot,want);
            if (got<=0) break;
            c.hdrGot+=got;
            if (c.hdrGot<3) break;
            c.rxType=c.hdr[0]; c.rxLen=((uint16_t)c.hdr[1]<<8)|c.hdr[2]; c.rxGot=0;
            if (c.rxLen>255){ Serial.println("[NET] oversize frame — dropping client"); dropClient(c,"dropped (oversize frame)"); return; }
            if (c.rxLen==0){ c.lastSeenMs=millis(); c.hdrGot=0; continue; } // empty keepalive
        } else {
            int want=c.rxLen-c.rxGot;
            int got=c.sock.read(c.rxBuf+c.rxGot,want);
            if (got<=0) break;
            c.rxGot+=got;
            if (c.rxGot>=c.rxLen){ c.lastSeenMs=millis(); dispatchClientFrame(c,idx); c.hdrGot=0; }
        }
    }
}
void acceptAndPoll(){
    WiFiClient nc=tcpServer.available();
    if (nc){
        int slot=-1; for (int i=0;i<MAX_CLIENTS;i++) if(!clients[i].used){ slot=i; break; }
        if (slot>=0){
            // Field by field: a `c = {}` temporary would put ~4.6 KB on the loop stack.
            DtnClient& c=clients[slot];
            c.sock=nc; c.nodeId=0; c.used=true; c.greeted=false; c.pings=false; c.lastSeenMs=millis();
            c.hdrGot=0; c.rxType=0; c.rxLen=0; c.rxGot=0; c.txLen=0; c.replayIdx=-1; c.replayed=0;
            c.sock.setNoDelay(true);
            // TCP keepalive finds a phone that vanished without closing (walked away, Wi-Fi off)
            // within ~1.5 min, PING or not — lwIP's default would wait 2 hours.
            int fd=c.sock.fd();
            if (fd>=0){
                int on=1, idle=60, intvl=10, cnt=3;
                setsockopt(fd,SOL_SOCKET,SO_KEEPALIVE,&on,sizeof(on));
                setsockopt(fd,IPPROTO_TCP,TCP_KEEPIDLE,&idle,sizeof(idle));
                setsockopt(fd,IPPROTO_TCP,TCP_KEEPINTVL,&intvl,sizeof(intvl));
                setsockopt(fd,IPPROTO_TCP,TCP_KEEPCNT,&cnt,sizeof(cnt));
            }
        } else { nc.stop(); } // full
    }
    uint32_t now=millis();
    for (int i=0;i<MAX_CLIENTS;i++){
        DtnClient& c=clients[i];
        if (!c.used) continue;
        if (!c.sock.connected()){ dropClient(c,"dropped"); continue; }
        if (!c.greeted){ uint8_t id[4]; wr32(id,HUB_ID); if (enqueueFrame(c,FRAME_HELLO,id,4)) c.greeted=true; }
        readClient(c,i);
        if (!c.used) continue;
        pumpReplay(c);
        flushClient(c);
        // Signed: readClient may have stamped lastSeenMs a millisecond after `now`, and
        // an unsigned `now - lastSeenMs` then wraps to ~49 days → instant "idle" drop.
        if (c.pings && (int32_t)(millis()-c.lastSeenMs) > (int32_t)CLIENT_IDLE_MS) dropClient(c,"timed out (idle)");
    }
    (void)now;
    maybeSendRoster();
}

// ============================================================================
//  Battery / OLED
// ============================================================================
float readBatteryVolts(){
    pinMode(PIN_VBAT_CTRL,OUTPUT); digitalWrite(PIN_VBAT_CTRL,LOW); delay(3);
    uint32_t mv=analogReadMilliVolts(PIN_VBAT_ADC);
    pinMode(PIN_VBAT_CTRL,INPUT);
    return mv*4.9f/1000.0f;  // 390k/100k divider (approx; V3.0/3.1)
}
void drawOled(){
    if (!oledOk) return;
    char line[32];
    u8g2.clearBuffer();
    u8g2.setFont(u8g2_font_6x10_tf);
    char hid[10]; formatNodeId(HUB_ID,hid);
    u8g2.drawStr(0,9,"DTN LoRa Hub");
    u8g2.drawStr(0,20,hid);
    snprintf(line,sizeof(line),"%s %.1fMHz",CFG.name,CFG.freq); u8g2.drawStr(0,31,line);
    snprintf(line,sizeof(line),"ph:%d hub:%d msg:%d",countConnectedPhones(),countActivePeers(),countWaitingMessages()); u8g2.drawStr(0,42,line);
    snprintf(line,sizeof(line),"fwd:%lu air:%.1f%%",(unsigned long)stats.msgsForwarded,airtimePct()); u8g2.drawStr(0,53,line);
    snprintf(line,sizeof(line),"up:%lus %.2fV",(unsigned long)(millis()/1000),readBatteryVolts()); u8g2.drawStr(0,63,line);
    u8g2.sendBuffer();
}
void maybeDrawOled(){ uint32_t now=millis(); if((int32_t)(now-nextOledMs)<0) return; nextOledMs=now+OLED_INTERVAL_MS; drawOled(); }

// ============================================================================
//  Serial dashboard + CLI
// ============================================================================
int countActivePeers(){ int n=0; for(int i=0;i<MAX_KNOWN_PEERS;i++) if(knownPeers[i].valid&&knownPeers[i].active)n++; return n; }
int countInactivePeers(){ int n=0; for(int i=0;i<MAX_KNOWN_PEERS;i++) if(knownPeers[i].valid&&!knownPeers[i].active)n++; return n; }
int countConnectedPhones(){ int n=0; for(int i=0;i<MAX_CLIENTS;i++) if(clients[i].used&&clients[i].sock.connected())n++; return n; }
// Messages actually waiting for delivery (DMs). Announces, broadcasts, group messages and
// receipts also sit in the buffer until their TTL — by design — but they wait for nobody.
int countWaitingMessages(){ int n=0; for(int i=0;i<BUFFER_CAPACITY;i++) if(buffer[i].valid&&buffer[i].msgType==MSGTYPE_DATA&&buffer[i].dest!=BROADCAST_DEST)n++; return n; }

void printDashboard(){
    refreshPeerActivity();
    char hid[10]; formatNodeId(HUB_ID,hid);
    Serial.println("\n==================== DTN HUB ====================");
    Serial.printf("Host %s  region=%s %.3fMHz SF%u BW%.0f pwr=%ddBm duty=%u%%\n",
                  hid, CFG.name, CFG.freq, CFG.sf, CFG.bw, CFG.power, CFG.dutyPct);
    Serial.printf("uptime=%lus  phones=%d  lora-nodes(active/stale)=%d/%d  airtime=%.2f%%\n",
                  (unsigned long)(millis()/1000), countConnectedPhones(), countActivePeers(), countInactivePeers(), airtimePct());
    uint32_t now=millis();
    for (int i=0;i<MAX_CLIENTS;i++){ DtnClient& c=clients[i]; if(!c.used) continue;
        char id[10]; formatNodeId(c.nodeId,id);
        int32_t idleMs=(int32_t)(now-c.lastSeenMs); if (idleMs<0) idleMs=0;
        Serial.printf("  phone %s %s idle=%lds txq=%dB\n", c.nodeId?id:"(unregistered)", c.sock.connected()?"up":"down",
                      (long)(idleMs/1000), c.txLen); }
    for (int i=0;i<MAX_KNOWN_PEERS;i++){ if(!knownPeers[i].valid) continue;
        char id[10]; formatNodeId(knownPeers[i].nodeId,id);
        Serial.printf("  lora %s rssi=%d dist~%.0fm %s\n", id, knownPeers[i].lastRssi,
                      estimateDistanceM(knownPeers[i].lastRssi), knownPeers[i].active?"ACTIVE":"stale"); }
    Serial.printf("msgs waiting=%d  ", countWaitingMessages());
    Serial.printf("buf=%lu/%d peak=%lu fwd=%lu dlv=%lu acks=%lu dup=%lu supp=%lu cad=%lu duty=%lu hop=%lu ttl=%lu\n",
                  (unsigned long)stats.bufferedNow,BUFFER_CAPACITY,(unsigned long)peakBufferUsage,
                  (unsigned long)stats.msgsForwarded,(unsigned long)stats.msgsDelivered,(unsigned long)stats.acksReceived,
                  (unsigned long)stats.duplicatesIgnored,(unsigned long)stats.suppressed,(unsigned long)stats.cadBusy,
                  (unsigned long)stats.dutyBlocked,(unsigned long)stats.hopDropped,(unsigned long)stats.ttlExpired);
    Serial.printf("phones: in=%lu out=%lu dropped(queue full)=%lu\n",
                  (unsigned long)stats.fromPhones,(unsigned long)stats.toPhones,(unsigned long)stats.txDropped);
    Serial.println("cmd: region in|eu|us  |  stats  (current region persists in NVS)");
    Serial.println("================================================\n");
}
void maybePrintDashboard(){ uint32_t now=millis(); if((int32_t)(now-nextDashboardMs)<0) return; nextDashboardMs=now+DASHBOARD_INTERVAL_MS; printDashboard(); }

void applyRegion(int idx, bool persist){
    if (idx<0||idx>=NUM_REGIONS) return;
    regionIdx=idx;
    if (persist){ prefs.begin("dtn",false); prefs.putInt("region",idx); prefs.end(); }
    Serial.printf("[CFG] region -> %s (%.3f MHz). Rebooting to re-init radio...\n", CFG.name, CFG.freq);
    delay(300); ESP.restart();
}
void handleSerialCli(){
    static char buf[32]; static int n=0;
    while (Serial.available()){
        char ch=Serial.read();
        if (ch=='\n'||ch=='\r'){ buf[n]=0;
            if (n>0){
                if      (!strncmp(buf,"region in",9)) applyRegion(0,true);
                else if (!strncmp(buf,"region eu",9)) applyRegion(1,true);
                else if (!strncmp(buf,"region us",9)) applyRegion(2,true);
                else if (!strcmp(buf,"stats")) printDashboard();
            }
            n=0;
        } else if (n<(int)sizeof(buf)-1){ buf[n++]=ch; }
    }
}

// ============================================================================
//  setup / loop
// ============================================================================
void deriveIdentity(){
    uint64_t mac=ESP.getEfuseMac();
    HUB_ID=(uint32_t)(mac & 0xFFFFFFFFu);
    if (HUB_ID==0 || HUB_ID==BROADCAST_DEST) HUB_ID=0xA0000001;
    snprintf(AP_SSID,sizeof(AP_SSID),"DTN-HUB-%04X",(unsigned)(HUB_ID & 0xFFFF));
}
void initOled(){
    pinMode(PIN_VEXT,OUTPUT); digitalWrite(PIN_VEXT,LOW); delay(50); // power peripherals
    oledOk=u8g2.begin();
    if (oledOk){ u8g2.clearBuffer(); u8g2.setFont(u8g2_font_6x10_tf); u8g2.drawStr(0,20,"DTN hub boot..."); u8g2.sendBuffer(); }
}
void initRadio(){
    loraSpi.begin(PIN_LORA_SCK,PIN_LORA_MISO,PIN_LORA_MOSI,PIN_LORA_NSS);
    int st=radio.begin(CFG.freq,CFG.bw,CFG.sf,CFG.cr,NET_SYNC,CFG.power,PREAMBLE,TCXO_VOLTAGE,false);
    if (st!=RADIOLIB_ERR_NONE){
        Serial.printf("LoRa begin=%d with TCXO — retrying on XTAL\n",st);
        st=radio.begin(CFG.freq,CFG.bw,CFG.sf,CFG.cr,NET_SYNC,CFG.power,PREAMBLE,0.0f,false);
    }
    if (st!=RADIOLIB_ERR_NONE){ Serial.printf("LoRa init failed (%d) — running WiFi-only\n",st); return; }
    radio.setCurrentLimit(140.0);
    radio.setRxBoostedGainMode(true);
    radio.setDio2AsRfSwitch(true);
    radio.setDio1Action(onLoRaIrq);
    radio.startReceive();
    Serial.printf("LoRa up: %s %.3fMHz SF%u BW%.0f CR4/%u pwr=%ddBm\n",CFG.name,CFG.freq,CFG.sf,CFG.bw,CFG.cr,CFG.power);
}
void setup(){
    Serial.setTxBufferSize(2048);
    Serial.begin(115200); delay(200);
    Serial.printf("\nDTN LoRa Hub firmware v%d booting\n", DTN_FW_VERSION);
    randomSeed(esp_random());

    prefs.begin("dtn",true); regionIdx=prefs.getInt("region",DTN_DEFAULT_REGION); prefs.end();
    if (regionIdx<0||regionIdx>=NUM_REGIONS) regionIdx=DTN_DEFAULT_REGION;

    for (int i=0;i<BUFFER_CAPACITY;i++) buffer[i].valid=false;
    for (int i=0;i<MAX_KNOWN_PEERS;i++) knownPeers[i].valid=false;
    memset(dedupKey,0,sizeof(dedupKey)); memset(ackDedupKey,0,sizeof(ackDedupKey));

    deriveIdentity();
    initOled();

    WiFi.mode(WIFI_AP);
    WiFi.softAP(AP_SSID,AP_PASS,1,0,MAX_CLIENTS);
    tcpServer.begin(); tcpServer.setNoDelay(true);
    Serial.printf("SoftAP %s up at %s\n",AP_SSID,WiFi.softAPIP().toString().c_str());

    initRadio();

    esp_task_wdt_init(WDT_TIMEOUT_S,true); esp_task_wdt_add(NULL);

    char hid[10]; formatNodeId(HUB_ID,hid);
    Serial.printf("Hub %s ready (region %s)\n",hid,CFG.name);
    uint32_t now=millis();
    nextDashboardMs=now+DASHBOARD_INTERVAL_MS; nextHeartbeatMs=now+2000; nextOledMs=now+500;
}
void loop(){
    esp_task_wdt_reset();
    acceptAndPoll();
    serviceRadio();
    pumpLoRaTx();
    maybeSendHeartbeat();
    bufferSweepExpired();
    maybeDrawOled();
    maybePrintDashboard();
    handleSerialCli();
}
