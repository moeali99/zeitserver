#!/usr/bin/env python3
"""DS3231 über Linux-I2C lesen (ohne i2c-tools). Ausgabe: ISO-8601 UTC oder Fehlercode."""
from __future__ import annotations

import argparse
import fcntl
import struct
import sys
from datetime import datetime
from zoneinfo import ZoneInfo

I2C_SLAVE = 0x0703
I2C_SMBUS = 0x0720
I2C_SMBUS_READ_BYTE_DATA = 2


def bcd(v: int) -> int:
    return (v & 0x0F) + ((v >> 4) & 0x0F) * 10


def smbus_read_byte(fd: int, reg: int) -> int:
    data = struct.pack("=BB", I2C_SMBUS_READ_BYTE_DATA, reg)
    buf = fcntl.ioctl(fd, I2C_SMBUS, data)
    return struct.unpack("=B", buf)[1]


def read_ds3231(bus: int, addr: int) -> datetime:
    path = f"/dev/i2c-{bus}"
    with open(path, "rb+", buffering=0) as dev:
        fcntl.ioctl(dev, I2C_SLAVE, addr)
        sec_reg = smbus_read_byte(dev.fileno(), 0x00)
        if sec_reg & 0x80:
            raise RuntimeError("RTC_CH=1 (angehalten) — hwclock -w nötig")
        minute = bcd(smbus_read_byte(dev.fileno(), 0x01) & 0x7F)
        hour = bcd(smbus_read_byte(dev.fileno(), 0x02) & 0x3F)
        day = bcd(smbus_read_byte(dev.fileno(), 0x04) & 0x3F)
        month_reg = smbus_read_byte(dev.fileno(), 0x05)
        month = bcd(month_reg & 0x1F)
        year_reg = smbus_read_byte(dev.fileno(), 0x06)
        year = 2000 + bcd(year_reg)
        if month_reg & 0x80:
            year += 100
        return datetime(year, month, day, hour, minute, bcd(sec_reg & 0x7F))


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--bus", type=int, required=True)
    p.add_argument("--addr", type=lambda x: int(x, 0), default=0x68)
    args = p.parse_args()
    try:
        local = read_ds3231(args.bus, args.addr)
        # Chip führt UTC; nicht als Europe/Berlin interpretieren.
        utc = local.replace(tzinfo=ZoneInfo("UTC"))
        print(utc.strftime("%Y-%m-%dT%H:%M:%SZ"))
        return 0
    except OSError as e:
        print(f"ERR:{e}", file=sys.stderr)
        return 2
    except Exception as e:
        print(f"ERR:{e}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    sys.exit(main())
