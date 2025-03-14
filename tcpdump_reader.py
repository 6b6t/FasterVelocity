#!/usr/bin/env python3

import sys
import os
import struct
import datetime
import argparse
from collections import defaultdict

def read_varint(data):
    """Read a VarInt from a bytes object."""
    value = 0
    position = 0

    for i in range(min(5, len(data))):
        current_byte = data[i]
        value |= (current_byte & 0x7F) << (position * 7)
        position += 1

        if (current_byte & 0x80) == 0:
            return value, i + 1  # Return value and number of bytes read

    # If we read 5 bytes and still haven't reached the end, it's an invalid VarInt
    return None, 0

def bytes_to_hex(data, max_length=100):
    """Convert bytes to a readable hex format with a maximum length."""
    if not data:
        return "Empty"

    # Convert to hex and insert spaces every 2 characters for readability
    hex_str = ' '.join(f"{b:02x}" for b in data[:max_length])

    if len(data) > max_length:
        hex_str += " ..."

    return hex_str

def parse_tcpdump(filename):
    """Parse a tcpdump file and return packet information."""
    packets = []
    with open(filename, 'rb') as f:
        while True:
            # Read packet header
            timestamp_data = f.read(8)
            if not timestamp_data or len(timestamp_data) < 8:
                break

            timestamp = struct.unpack('>Q', timestamp_data)[0]  # 8-byte long timestamp

            # Read direction (1 byte)
            direction_byte = f.read(1)
            if not direction_byte:
                break

            direction_value = direction_byte[0]
            # Convert direction value to string
            direction = "S2C" if direction_value == 0 else "C2S"

            # Read packet length
            packet_len_data = f.read(4)
            if not packet_len_data or len(packet_len_data) < 4:
                break

            packet_len = struct.unpack('>I', packet_len_data)[0]  # 4-byte packet length

            # Read the actual packet data
            packet_data = f.read(packet_len)
            if len(packet_data) < packet_len:
                break

            # Try to parse the first VarInt
            first_varint = None
            if packet_data:
                first_varint, _ = read_varint(packet_data)
#            if direction == "S2C": continue

            packets.append({
                'timestamp': timestamp,
                'direction': direction,
                'direction_value': direction_value,
                'length': packet_len,
                'datetime': datetime.datetime.fromtimestamp(timestamp / 1000),
                'first_varint': first_varint,
                'data': packet_data  # Store the entire packet data
            })

            # Print the packet info immediately with hex representation
            print(f"[{direction} ({direction_value})] Length: {packet_len}, First VarInt: {first_varint}")
            print(f"Data: {bytes_to_hex(packet_data)}")
            print("-" * 80)

    return packets

def analyze_packets(packets):
    """Analyze packets and return statistics."""
    if not packets:
        return "No packets found"

    # Separate by direction
    directions = defaultdict(list)
    for packet in packets:
        directions[packet['direction']].append(packet)

    results = []
    total_packets = len(packets)
    total_bytes = sum(p['length'] for p in packets)

    results.append(f"Total packets: {total_packets}")
    results.append(f"Total bytes: {total_bytes:,}")

    # VarInt stats
    packets_with_varint = sum(1 for p in packets if p['first_varint'] is not None)
    results.append(f"Packets with valid first VarInt: {packets_with_varint} ({packets_with_varint/total_packets*100:.1f}%)")

    # Time range
    start_time = min(p['datetime'] for p in packets)
    end_time = max(p['datetime'] for p in packets)
    duration = (end_time - start_time).total_seconds()

    results.append(f"Time range: {start_time.isoformat()} to {end_time.isoformat()}")
    results.append(f"Duration: {duration:.2f} seconds")

    if duration > 0:
        results.append(f"Average throughput: {total_bytes / duration:.2f} bytes/sec")

    # Stats by direction
    for direction, dir_packets in directions.items():
        dir_bytes = sum(p['length'] for p in dir_packets)
        results.append("\n" + "=" * 50)
        results.append(f"Direction: {direction}")
        results.append(f"Packets: {len(dir_packets)} ({len(dir_packets)/total_packets*100:.1f}% of total)")
        results.append(f"Bytes: {dir_bytes:,} ({dir_bytes/total_bytes*100:.1f}% of total)")

        # VarInt stats by direction
        dir_varints = [p['first_varint'] for p in dir_packets if p['first_varint'] is not None]
        if dir_varints:
            results.append(f"Packets with valid first VarInt: {len(dir_varints)} ({len(dir_varints)/len(dir_packets)*100:.1f}%)")

            # Count occurrences of each VarInt
            varint_counts = {}
            for v in dir_varints:
                varint_counts[v] = varint_counts.get(v, 0) + 1

            # Show most common VarInts
            results.append("\nMost common first VarInts:")
            for varint, count in sorted(varint_counts.items(), key=lambda x: x[1], reverse=True)[:10]:
                results.append(f"  VarInt {varint} (0x{varint:02x}): {count} occurrences ({count/len(dir_varints)*100:.1f}%)")

        # Packet size stats
        min_size = min(p['length'] for p in dir_packets)
        max_size = max(p['length'] for p in dir_packets)
        avg_size = dir_bytes / len(dir_packets)

        results.append(f"\nMinimum packet size: {min_size} bytes")
        results.append(f"Maximum packet size: {max_size} bytes")
        results.append(f"Average packet size: {avg_size:.2f} bytes")

        # Time distribution
        start_dir = min(p['datetime'] for p in dir_packets)
        end_dir = max(p['datetime'] for p in dir_packets)
        dir_duration = (end_dir - start_dir).total_seconds()

        results.append(f"\nFirst packet: {start_dir.isoformat()}")
        results.append(f"Last packet: {end_dir.isoformat()}")

        if dir_duration > 0:
            packets_per_sec = len(dir_packets) / dir_duration
            bytes_per_sec = dir_bytes / dir_duration
            results.append(f"Packets per second: {packets_per_sec:.2f}")
            results.append(f"Bytes per second: {bytes_per_sec:.2f}")

    return "\n".join(results)

def main():
    parser = argparse.ArgumentParser(description='Parse and analyze tcpdump files')
    parser.add_argument('files', metavar='FILE', nargs='+', help='tcpdump files to analyze')
    parser.add_argument('--max-hex', type=int, default=100, help='Maximum number of bytes to display in hex output')
    args = parser.parse_args()

    for filename in args.files:
        if not os.path.exists(filename):
            print(f"Error: File {filename} not found", file=sys.stderr)
            continue

        print(f"\n{'#'*80}\nAnalyzing: {filename}\n{'#'*80}")
        try:
            packets = parse_tcpdump(filename)
            analysis = analyze_packets(packets)
            print("\nSummary Analysis:")
            print(analysis)
        except Exception as e:
            print(f"Error analyzing {filename}: {e}", file=sys.stderr)

if __name__ == "__main__":
    main()

