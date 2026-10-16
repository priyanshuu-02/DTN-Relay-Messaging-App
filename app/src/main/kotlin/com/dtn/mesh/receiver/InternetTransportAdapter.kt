package com.dtn.mesh.receiver

import android.util.Log
import com.dtn.mesh.crypto.DtnIdentity
import com.dtn.mesh.model.DtnMessage
import com.dtn.mesh.model.NodeId
import com.dtn.mesh.settings.SettingsRepository
import com.hivemq.client.mqtt.MqttClient
import com.hivemq.client.mqtt.datatypes.MqttQos
import com.hivemq.client.mqtt.mqtt5.Mqtt5AsyncClient
import com.hivemq.client.mqtt.mqtt5.message.publish.Mqtt5Publish
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withTimeoutOrNull
import java.util.UUID
import java.util.function.Consumer
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Optional internet transport. Bridges the DTN mesh to an MQTT broker so phones with internet can
 * exchange bundles when out of radio range. Bundles stay end-to-end encrypted (the broker only
 * ever sees ciphertext + the routing header), so a public broker is acceptable.
 *
 * Topic scheme (root `dtn/v1`):
 *  - `dtn/v1/node/<destHex>` — unicast DMs addressed to a specific node id.
 *  - `dtn/v1/bcast`          — broadcasts, group messages and announces.
 *
 * This transport is NOT part of the local send-priority chain. Instead the origin publishes each
 * outgoing bundle once (see OutboundMessageService). Inbound bundles are merged into the
 * orchestrator like any other transport and deduped by message id, so there are no echo loops.
 * It is opt-in (Settings → Internet) and defaults OFF.
 */
@Singleton
class InternetTransportAdapter @Inject constructor(
    @ApplicationContext private val context: Context,
    private val identity: DtnIdentity,
    private val settings: SettingsRepository,
) : DtnTransport {

    override val transportName: String = "Internet (MQTT)"

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var client: Mqtt5AsyncClient? = null

    private val _inboundMessages = MutableSharedFlow<InboundPacket>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val inboundMessages: Flow<InboundPacket> = _inboundMessages.asSharedFlow()

    private val _nodeEvents = MutableSharedFlow<NodeEncounterEvent>(
        extraBufferCapacity = 8, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val nodeEvents: Flow<NodeEncounterEvent> = _nodeEvents.asSharedFlow()

    private val _deliveryStatus = MutableSharedFlow<DeliveryStatusEvent>(
        extraBufferCapacity = 16, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    override val deliveryStatus: Flow<DeliveryStatusEvent> = _deliveryStatus.asSharedFlow()

    private val _connectionState = MutableStateFlow(MeshConnectionState.DISCONNECTED)
    override val connectionState: StateFlow<MeshConnectionState> = _connectionState.asStateFlow()

    override val isConnected: Boolean get() = _connectionState.value == MeshConnectionState.CONNECTED
    override val localNodeId: NodeId get() = identity.nodeId

    private fun hex(num: Int): String = String.format("%08x", num)
    private fun nodeTopic(num: Int) = "$TOPIC_ROOT/node/${hex(num)}"

    override suspend fun connect(): Boolean {
        if (isConnected) return true
        _connectionState.value = MeshConnectionState.CONNECTING
        val url = runCatching { settings.brokerUrlOnce() }.getOrDefault(SettingsRepository.DEFAULT_BROKER)
        val (host, port) = parseBroker(url)
        return try {
            val c = MqttClient.builder()
                .useMqttVersion5()
                .identifier("dtn-${hex(identity.nodeNum32)}-${UUID.randomUUID().toString().take(6)}")
                .serverHost(host)
                .serverPort(port)
                .automaticReconnectWithDefaultConfig()
                .buildAsync()
            client = c

            val connected = CompletableDeferred<Boolean>()
            c.connectWith().cleanStart(true).keepAlive(60).send().whenComplete { _, ex ->
                if (ex == null) connected.complete(true) else connected.completeExceptionally(ex)
            }
            val ok = withTimeoutOrNull(10_000) { runCatching { connected.await() }.getOrDefault(false) } ?: false
            if (!ok) { _connectionState.value = MeshConnectionState.DISCONNECTED; return false }

            val onPublish = Consumer<Mqtt5Publish> { pub -> onInbound(pub.payloadAsBytes) }
            c.subscribeWith().topicFilter(nodeTopic(identity.nodeNum32)).qos(MqttQos.AT_LEAST_ONCE).callback(onPublish).send()
            c.subscribeWith().topicFilter(BCAST_TOPIC).qos(MqttQos.AT_LEAST_ONCE).callback(onPublish).send()

            _connectionState.value = MeshConnectionState.CONNECTED
            Log.i(TAG, "Connected to MQTT broker $host:$port as ${identity.nodeId}")
            true
        } catch (e: Exception) {
            Log.e(TAG, "MQTT connect failed", e)
            _connectionState.value = MeshConnectionState.DISCONNECTED
            runCatching { client?.disconnect() }
            client = null
            false
        }
    }

    override fun disconnect() {
        runCatching { client?.disconnect() }
        client = null
        _connectionState.value = MeshConnectionState.DISCONNECTED
    }

    /** Publish an outgoing bundle to the relay (called once at the origin). */
    fun publish(message: DtnMessage) {
        val c = client ?: return
        if (!isConnected) return
        runCatching {
            val bytes = DtnWireCodec.encode(message)
            val topic = if (message.destinationNodeId == NodeId.BROADCAST) {
                BCAST_TOPIC
            } else {
                nodeTopic(message.destinationNodeId.toNodeNum32())
            }
            c.publishWith().topic(topic).qos(MqttQos.AT_LEAST_ONCE).payload(bytes).send()
        }.onFailure { Log.w(TAG, "MQTT publish failed", it) }
    }

    /** Not used for routing — the origin publishes directly via [publish]. */
    override suspend fun sendMessage(message: DtnMessage, targetPeer: NodeId): Int? = null

    private fun onInbound(payload: ByteArray) {
        val msg = DtnWireCodec.decode(payload, rssi = 0, snr = 0f) ?: return
        // No viaPeer: the relay is not an attributable neighbour (routing summaries from it are
        // ignored; DATA/ANNOUNCE are deduped by message id in the orchestrator).
        _inboundMessages.tryEmit(InboundPacket(message = msg, channel = msg.channel, viaPeer = null))
    }

    private fun parseBroker(url: String): Pair<String, Int> {
        val stripped = url.substringAfter("://", url)
        val host = stripped.substringBefore(":").ifBlank { "broker.hivemq.com" }
        val port = stripped.substringAfter(":", "1883").toIntOrNull() ?: 1883
        return host to port
    }

    companion object {
        private const val TAG = "InternetTransport"
        private const val TOPIC_ROOT = "dtn/v1"
        private const val BCAST_TOPIC = "$TOPIC_ROOT/bcast"
    }
}
