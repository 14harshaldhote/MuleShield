package io.muleshield.risk.stream;

import org.apache.kafka.common.serialization.Deserializer;
import org.apache.kafka.common.serialization.Serde;
import org.apache.kafka.common.serialization.Serializer;

import tools.jackson.databind.json.JsonMapper;

/** JSON values for Kafka Streams, with the same mapper as the REST API. */
final class JsonSerde<T> implements Serde<T> {

    private final JsonMapper json;
    private final Class<T> type;

    JsonSerde(JsonMapper json, Class<T> type) {
        this.json = json;
        this.type = type;
    }

    @Override
    public Serializer<T> serializer() {
        return (topic, value) -> value == null ? null : json.writeValueAsBytes(value);
    }

    @Override
    public Deserializer<T> deserializer() {
        return (topic, bytes) -> bytes == null ? null : json.readValue(bytes, type);
    }
}
