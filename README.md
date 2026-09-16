# ROG 5 Dual Wi-Fi & Gaming SLA Manager

Dedicated Dual Wi-Fi (Multi-STA concurrency) and Qualcomm SLA / SLS network bonding manager for the **ASUS ROG Phone 5 (I005D / ANAKIN2)** running AOSP / Treble GSIs.

---

## Overview

On generic AOSP / Treble GSIs, the secondary Wi-Fi antenna (`wlan1`) remains unmanaged because generic Android does not bundle Asus's proprietary `vicewifi-service.jar` framework stack.

This application directly bridges the gap on Treble GSIs:
1. **Multi-STA Control:** Spawns and manages the secondary `wlan1` station interface on the Qualcomm FastConnect 6900 Wi-Fi chip.
2. **Dual Network Authentication:** Communicates with `wpa_supplicant` to concurrently authenticate with a secondary access point (e.g. 5 GHz while primary `wlan0` is on 2.4 GHz).
3. **Policy Routing:** Configures Linux policy routing table `1028` and assigns `fwmark 0x5c` to `wlan1` (matching stock Asus routing conventions).
4. **Qualcomm SLA / SLS Bonding:** Activates the kernel SLA module (`/proc/sla/config`) and Qualcomm `slad-v2` daemon to enable link aggregation and low-latency packet steering for games and streaming apps.

---

## Requirements

- **Device:** ASUS ROG Phone 5 / 5s (I005D / I006D)
- **ROM:** Any AOSP / Treble GSI (Android 12+)
- **Root:** Magisk or KernelSU (required for policy routing table rules and `/proc/sla/config`)

---

## Automated Builds

Pre-compiled APKs are automatically built and published on every commit via GitHub Actions under the **Actions** tab.
