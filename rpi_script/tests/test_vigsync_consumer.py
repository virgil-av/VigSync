import importlib.util
import json
import sys
import tempfile
import types
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest import mock


def load_consumer_module():
    client_module = types.ModuleType("paho.mqtt.client")
    client_module.MQTT_ERR_SUCCESS = 0
    client_module.MQTT_ERR_NO_CONN = 4

    mqtt_module = types.ModuleType("paho.mqtt")
    mqtt_module.client = client_module

    paho_module = types.ModuleType("paho")
    paho_module.mqtt = mqtt_module

    sys.modules.setdefault("paho", paho_module)
    sys.modules.setdefault("paho.mqtt", mqtt_module)
    sys.modules.setdefault("paho.mqtt.client", client_module)

    module_path = Path(__file__).resolve().parents[1] / "vigsync_consumer.py"
    spec = importlib.util.spec_from_file_location("vigsync_consumer_under_test", module_path)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


vc = load_consumer_module()


class FakeMqttClient:
    def __init__(self, connected=True, publish_rc=0):
        self.connected = connected
        self.publish_rc = publish_rc
        self.published = []

    def is_connected(self):
        return self.connected

    def publish(self, topic, payload, qos=0):
        self.published.append((topic, payload, qos))
        return SimpleNamespace(rc=self.publish_rc, mid=len(self.published))


class VigSyncConsumerTest(unittest.TestCase):
    def setUp(self):
        self.temp_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp_dir.cleanup)
        self.root = Path(self.temp_dir.name)

        vc.SPOOL_FILE = str(self.root / "spool.jsonl")
        vc.STATE_FILE = str(self.root / "consumer_state.json")
        vc.CONFIG_FILE = str(self.root / "vigsync_server_config.json")
        vc.LOG_FILE = str(self.root / "consumer.log")
        vc.memory_logs.clear()
        vc.seen_fingerprints.clear()
        vc.seen_fingerprint_set.clear()
        vc._loaded_state_file = None
        vc.pending_publish_by_mid.clear()
        vc.pending_spool_ids.clear()

    def read_spool(self):
        with open(vc.SPOOL_FILE, "r", encoding="utf-8") as handle:
            return [json.loads(line) for line in handle if line.strip()]

    def test_append_spool_writes_jsonl_record(self):
        vc.append_spool("vigsync/events/device", '{"type":"SMS"}')

        self.assertEqual(
            [{"topic": "vigsync/events/device", "payload": '{"type":"SMS"}'}],
            [{"topic": item["topic"], "payload": item["payload"]} for item in self.read_spool()],
        )

    def test_flush_spool_waits_for_mqtt_ack_before_removing_file(self):
        vc.append_spool("vigsync/events/device", '{"type":"SMS"}')
        client = FakeMqttClient(connected=True, publish_rc=vc.mqtt.MQTT_ERR_SUCCESS)

        vc.flush_spool(client)

        self.assertEqual([("vigsync/events/device", '{"type":"SMS"}', 1)], client.published)
        self.assertTrue(Path(vc.SPOOL_FILE).exists())

        vc.on_publish(client, None, 1)

        self.assertFalse(Path(vc.SPOOL_FILE).exists())

    def test_on_publish_removes_only_matching_spool_record(self):
        vc.append_spool("vigsync/events/device", '{"type":"SMS"}')
        vc.append_spool("vigsync/status/device", '{"type":"STATUS"}')
        client = FakeMqttClient(connected=True, publish_rc=vc.mqtt.MQTT_ERR_SUCCESS)

        vc.flush_spool(client)
        vc.on_publish(client, None, 1)

        self.assertEqual(
            [{"topic": "vigsync/status/device", "payload": '{"type":"STATUS"}'}],
            [{"topic": item["topic"], "payload": item["payload"]} for item in self.read_spool()],
        )

    def test_flush_spool_preserves_unacknowledged_records(self):
        vc.append_spool("vigsync/events/device", '{"type":"SMS"}')
        client = FakeMqttClient(connected=True, publish_rc=vc.mqtt.MQTT_ERR_SUCCESS)

        vc.flush_spool(client)

        self.assertEqual(
            [{"topic": "vigsync/events/device", "payload": '{"type":"SMS"}'}],
            [{"topic": item["topic"], "payload": item["payload"]} for item in self.read_spool()],
        )

    def test_flush_spool_does_not_republish_pending_records_before_ack(self):
        vc.append_spool("vigsync/events/device", '{"type":"SMS"}')
        client = FakeMqttClient(connected=True, publish_rc=vc.mqtt.MQTT_ERR_SUCCESS)

        vc.flush_spool(client)
        vc.flush_spool(client)

        self.assertEqual([("vigsync/events/device", '{"type":"SMS"}', 1)], client.published)

    def test_flush_spool_preserves_failed_publish_records(self):
        vc.append_spool("vigsync/events/device", '{"type":"SMS"}')
        client = FakeMqttClient(connected=True, publish_rc=vc.mqtt.MQTT_ERR_NO_CONN)

        vc.flush_spool(client)

        self.assertEqual(
            [{"topic": "vigsync/events/device", "payload": '{"type":"SMS"}'}],
            [{"topic": item["topic"], "payload": item["payload"]} for item in self.read_spool()],
        )

    def test_remember_fingerprint_rejects_duplicate_replay(self):
        self.assertTrue(vc.remember_fingerprint("topic", '{"type":"SMS"}'))
        self.assertFalse(vc.remember_fingerprint("topic", '{"type":"SMS"}'))
        self.assertTrue(vc.remember_fingerprint("topic", '{"type":"CALL"}'))

    def test_remember_fingerprint_persists_replay_cache(self):
        self.assertTrue(vc.remember_fingerprint("topic", '{"type":"SMS"}'))
        vc.seen_fingerprints.clear()
        vc.seen_fingerprint_set.clear()
        vc._loaded_state_file = None

        self.assertTrue(vc.has_fingerprint("topic", '{"type":"SMS"}'))

    def test_publish_or_spool_skips_malformed_json_payloads(self):
        client = FakeMqttClient(connected=False)

        vc.publish_or_spool(client, "vigsync/events/device", "not-json")

        self.assertFalse(Path(vc.SPOOL_FILE).exists())
        self.assertFalse(Path(vc.STATE_FILE).exists())
        self.assertEqual([], client.published)

    def test_publish_or_spool_spools_valid_payload_when_disconnected(self):
        client = FakeMqttClient(connected=False)

        vc.publish_or_spool(client, "vigsync/events/device", '{"type":"SMS"}')

        self.assertEqual(
            [{"topic": "vigsync/events/device", "payload": '{"type":"SMS"}'}],
            [{"topic": item["topic"], "payload": item["payload"]} for item in self.read_spool()],
        )
        self.assertTrue(vc.has_fingerprint("vigsync/events/device", '{"type":"SMS"}'))
        self.assertEqual([], client.published)

    def test_publish_or_spool_dedupes_replay_after_restart(self):
        client = FakeMqttClient(connected=False)

        vc.publish_or_spool(client, "vigsync/events/device", '{"type":"SMS"}')
        vc.seen_fingerprints.clear()
        vc.seen_fingerprint_set.clear()
        vc._loaded_state_file = None
        vc.publish_or_spool(client, "vigsync/events/device", '{"type":"SMS"}')

        self.assertEqual(1, len(self.read_spool()))
        self.assertEqual([], client.published)

    def test_publish_or_spool_keeps_connected_publish_spooled_until_ack(self):
        client = FakeMqttClient(connected=True)

        vc.publish_or_spool(client, "vigsync/events/device", '{"type":"SMS"}')

        self.assertEqual([("vigsync/events/device", '{"type":"SMS"}', 1)], client.published)
        self.assertEqual(1, len(self.read_spool()))

        vc.on_publish(client, None, 1)

        self.assertFalse(Path(vc.SPOOL_FILE).exists())

    def test_load_config_falls_back_to_existing_local_config_when_adb_pull_fails(self):
        expected = {"device_id": "phone-1", "broker_url": "localhost"}
        Path(vc.CONFIG_FILE).write_text(json.dumps(expected), encoding="utf-8")

        with mock.patch.object(vc, "pull_config_via_adb", return_value=False):
            self.assertEqual(expected, vc.load_config())

    def test_invalid_adb_config_output_does_not_overwrite_existing_config(self):
        existing = {"device_id": "phone-1", "broker_url": "localhost"}
        Path(vc.CONFIG_FILE).write_text(json.dumps(existing), encoding="utf-8")

        result = SimpleNamespace(returncode=0, stdout="{invalid-json", stderr="")
        with mock.patch.object(vc.subprocess, "run", return_value=result):
            self.assertFalse(vc.pull_config_via_adb())

        self.assertEqual(existing, json.loads(Path(vc.CONFIG_FILE).read_text(encoding="utf-8")))


if __name__ == "__main__":
    unittest.main()
