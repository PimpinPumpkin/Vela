#!/usr/bin/env python3
"""Print a PMTiles v3 archive's bounds as a JSON [S, W, N, E] array (the app's bbox order).
The header keeps them as int32 E7 at bytes 102..117: min lon, min lat, max lon, max lat.
Works on a local file or on just the first 127 bytes of one (a range request)."""
import struct, sys
data = open(sys.argv[1], 'rb').read(127)
if data[:7] != b'PMTiles':
    sys.exit("not a PMTiles archive")
min_lon, min_lat, max_lon, max_lat = struct.unpack_from('<iiii', data, 102)
print('[%.4f, %.4f, %.4f, %.4f]' % (min_lat / 1e7, min_lon / 1e7, max_lat / 1e7, max_lon / 1e7))
