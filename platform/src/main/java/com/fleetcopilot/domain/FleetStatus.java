package com.fleetcopilot.domain;

/**
 * Fleet-wide totals. Warning/error counts only consider online devices: the last status of an
 * offline device is stale, and the offline count already covers it.
 */
public record FleetStatus(int total, int online, int offline, int warning, int error) {}
