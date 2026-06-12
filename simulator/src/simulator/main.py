"""Entrypoint: run N devices and publish telemetry to MQTT on a fixed interval."""

from __future__ import annotations

import argparse
import heapq
import json
import logging
import os
import random
import signal
import threading
import time

import paho.mqtt.client as mqtt

from simulator import faults
from simulator.device import Device

log = logging.getLogger("simulator")

STATS_EVERY_S = 30.0
CONNECT_RETRY_S = 2.0


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Fleet Copilot device fleet simulator")
    parser.add_argument(
        "--devices",
        type=int,
        default=int(os.getenv("DEVICES", "15")),
        help="number of simulated devices (env DEVICES, default 15)",
    )
    parser.add_argument(
        "--interval",
        type=float,
        default=float(os.getenv("INTERVAL", "3")),
        help="seconds between publishes per device (env INTERVAL, default 3)",
    )
    parser.add_argument(
        "--mqtt-host",
        default=os.getenv("MQTT_HOST", "localhost"),
        help="MQTT broker host (env MQTT_HOST, default localhost)",
    )
    parser.add_argument(
        "--mqtt-port",
        type=int,
        default=int(os.getenv("MQTT_PORT", "1883")),
        help="MQTT broker port (env MQTT_PORT, default 1883)",
    )
    parser.add_argument(
        "--fault",
        action="append",
        metavar="DEVICE_ID:FAULT",
        help=(
            "inject a fault at startup, e.g. dev-042:silent (repeatable; also via env "
            f"FAULTS as comma-separated specs; faults: {', '.join(faults.FAULTS)})"
        ),
    )
    return parser.parse_args()


def startup_fault_specs(args: argparse.Namespace) -> list[str]:
    specs = [s.strip() for s in os.getenv("FAULTS", "").split(",") if s.strip()]
    specs += args.fault or []
    return specs


def apply_startup_faults(specs: list[str], devices_by_id: dict[str, Device]) -> None:
    for spec in specs:
        try:
            device_id, fault = faults.parse_fault_spec(spec)
        except ValueError as exc:
            log.warning("Ignoring fault spec: %s", exc)
            continue
        device = devices_by_id.get(device_id)
        if device is None:
            log.warning("Ignoring fault spec '%s': unknown device", spec)
            continue
        device.set_fault(fault)
        log.info("Startup fault '%s' on %s", fault, device_id)


def on_disconnect(client, userdata, flags, reason_code, properties) -> None:
    log.warning("Disconnected from broker (%s); automatic reconnect is active", reason_code)


def connect_with_retry(client: mqtt.Client, host: str, port: int, stop: threading.Event) -> bool:
    """Keep trying the initial connect so the simulator can start before the broker."""
    while not stop.is_set():
        try:
            client.connect(host, port, keepalive=60)
            return True
        except OSError as exc:
            log.warning(
                "Broker %s:%d not reachable (%s); retrying in %.0fs",
                host,
                port,
                exc,
                CONNECT_RETRY_S,
            )
            stop.wait(CONNECT_RETRY_S)
    return False


def run(
    args: argparse.Namespace,
    client: mqtt.Client,
    stop: threading.Event,
    devices: list[Device],
) -> None:
    start = time.monotonic()
    # Stagger initial publishes across one interval so the load is a smooth
    # stream (devices/interval msg/s) instead of one burst every interval.
    heap = [(start + (i / len(devices)) * args.interval, i, d) for i, d in enumerate(devices)]
    heapq.heapify(heap)

    published = failed = window_published = 0
    last_stats = start
    while not stop.is_set():
        due, seq, device = heap[0]
        now = time.monotonic()
        if due > now:
            stop.wait(min(due - now, 0.2))
            continue
        heapq.heappop(heap)
        heapq.heappush(heap, (due + args.interval, seq, device))
        payload = device.tick(args.interval)
        if payload is None:  # silenced by a fault
            continue
        result = client.publish(f"fleet/{device.device_id}/telemetry", json.dumps(payload), qos=1)
        if result.rc == mqtt.MQTT_ERR_SUCCESS:
            published += 1
            window_published += 1
        else:
            failed += 1

        now = time.monotonic()
        if now - last_stats >= STATS_EVERY_S:
            log.info(
                "published=%d failed=%d rate=%.1f msg/s",
                published,
                failed,
                window_published / (now - last_stats),
            )
            window_published = 0
            last_stats = now


def main() -> None:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    args = parse_args()

    stop = threading.Event()
    for sig in (signal.SIGINT, signal.SIGTERM):
        signal.signal(sig, lambda *_: stop.set())

    devices = [Device(f"dev-{i:03d}", random.Random()) for i in range(1, args.devices + 1)]
    devices_by_id = {d.device_id: d for d in devices}
    apply_startup_faults(startup_fault_specs(args), devices_by_id)

    def on_connect(client_, userdata, flags, reason_code, properties) -> None:
        if reason_code.is_failure:
            log.warning("Broker refused connection: %s", reason_code)
            return
        log.info("Connected to broker")
        client_.subscribe(faults.CONTROL_TOPIC, qos=1)
        log.info("Listening for fault commands on '%s'", faults.CONTROL_TOPIC)

    def on_message(client_, userdata, msg) -> None:
        try:
            device_id, fault = faults.parse_control(msg.payload)
        except ValueError as exc:
            log.warning("Ignoring control message: %s", exc)
            return
        device = devices_by_id.get(device_id)
        if device is None:
            log.warning("Ignoring control message for unknown device '%s'", device_id)
            return
        if fault == faults.CLEAR:
            device.set_fault(None)
            log.info("Cleared fault on %s", device_id)
        else:
            device.set_fault(fault)
            log.info("Injected fault '%s' into %s", fault, device_id)

    client = mqtt.Client(mqtt.CallbackAPIVersion.VERSION2, client_id="fleet-simulator")
    client.on_connect = on_connect
    client.on_message = on_message
    client.on_disconnect = on_disconnect
    client.reconnect_delay_set(min_delay=1, max_delay=30)

    log.info(
        "Starting %d devices, interval %.1fs, broker %s:%d",
        args.devices,
        args.interval,
        args.mqtt_host,
        args.mqtt_port,
    )
    if not connect_with_retry(client, args.mqtt_host, args.mqtt_port, stop):
        return
    client.loop_start()
    try:
        run(args, client, stop, devices)
    finally:
        client.disconnect()
        client.loop_stop()
        log.info("Simulator stopped (published messages may be truncated at shutdown)")


if __name__ == "__main__":
    main()
