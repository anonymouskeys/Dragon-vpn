#!/usr/bin/env python3
from pathlib import Path

ROOT = Path(__file__).resolve().parents[3] / ".." / "sing-box"
ROOT = ROOT.resolve()


def replace(path: str, old: str, new: str) -> None:
    target = ROOT / path
    text = target.read_text(encoding="utf-8")
    if old not in text:
        raise SystemExit(f"Core patch mismatch in {path}: expected block not found")
    target.write_text(text.replace(old, new, 1), encoding="utf-8")

# Add configurable fragment length and delay ranges to the JSON model.
replace(
    "option/tls.go",
    '''\tFragment              bool                       `json:"fragment,omitempty"`\n\tFragmentFallbackDelay badoption.Duration         `json:"fragment_fallback_delay,omitempty"`\n\tRecordFragment        bool                       `json:"record_fragment,omitempty"`''',
    '''\tFragment              bool                       `json:"fragment,omitempty"`\n\tFragmentFallbackDelay badoption.Duration         `json:"fragment_fallback_delay,omitempty"`\n\tFragmentLength        string                     `json:"fragment_length,omitempty"`\n\tFragmentInterval      string                     `json:"fragment_interval,omitempty"`\n\tRecordFragment        bool                       `json:"record_fragment,omitempty"`''',
)

# Standard TLS client: carry the two new values into the fragmentation wrapper.
replace(
    "common/tls/std_client.go",
    '''\tfragment              bool\n\tfragmentFallbackDelay time.Duration\n\trecordFragment        bool''',
    '''\tfragment              bool\n\tfragmentFallbackDelay time.Duration\n\tfragmentLength        string\n\tfragmentInterval      string\n\trecordFragment        bool''',
)
replace(
    "common/tls/std_client.go",
    '''tf.NewConn(conn, c.ctx, c.fragment, c.recordFragment, c.fragmentFallbackDelay)''',
    '''tf.NewConn(conn, c.ctx, c.fragment, c.recordFragment, c.fragmentFallbackDelay, c.fragmentLength, c.fragmentInterval)''',
)
replace(
    "common/tls/std_client.go",
    '''return &STDClientConfig{c.ctx, c.config.Clone(), c.fragment, c.fragmentFallbackDelay, c.recordFragment}''',
    '''return &STDClientConfig{c.ctx, c.config.Clone(), c.fragment, c.fragmentFallbackDelay, c.fragmentLength, c.fragmentInterval, c.recordFragment}''',
)
replace(
    "common/tls/std_client.go",
    '''stdConfig := &STDClientConfig{ctx, &tlsConfig, options.Fragment, time.Duration(options.FragmentFallbackDelay), options.RecordFragment}''',
    '''stdConfig := &STDClientConfig{ctx, &tlsConfig, options.Fragment, time.Duration(options.FragmentFallbackDelay), options.FragmentLength, options.FragmentInterval, options.RecordFragment}''',
)

# uTLS client: same parameters and behavior.
replace(
    "common/tls/utls_client.go",
    '''\tfragment              bool\n\tfragmentFallbackDelay time.Duration\n\trecordFragment        bool''',
    '''\tfragment              bool\n\tfragmentFallbackDelay time.Duration\n\tfragmentLength        string\n\tfragmentInterval      string\n\trecordFragment        bool''',
)
replace(
    "common/tls/utls_client.go",
    '''tf.NewConn(conn, c.ctx, c.fragment, c.recordFragment, c.fragmentFallbackDelay)''',
    '''tf.NewConn(conn, c.ctx, c.fragment, c.recordFragment, c.fragmentFallbackDelay, c.fragmentLength, c.fragmentInterval)''',
)
replace(
    "common/tls/utls_client.go",
    '''\t\tc.fragmentFallbackDelay,\n\t\tc.recordFragment,''',
    '''\t\tc.fragmentFallbackDelay,\n\t\tc.fragmentLength,\n\t\tc.fragmentInterval,\n\t\tc.recordFragment,''',
)
replace(
    "common/tls/utls_client.go",
    '''uConfig := &UTLSClientConfig{ctx, &tlsConfig, id, options.Fragment, time.Duration(options.FragmentFallbackDelay), options.RecordFragment}''',
    '''uConfig := &UTLSClientConfig{ctx, &tlsConfig, id, options.Fragment, time.Duration(options.FragmentFallbackDelay), options.FragmentLength, options.FragmentInterval, options.RecordFragment}''',
)

# Fragmentation engine. The existing SNI-aware split remains the fallback when
# no custom range is supplied. A range accepts N or N-M bytes / milliseconds.
replace(
    "common/tlsfragment/conn.go",
    '''\t"math/rand"\n\t"net"\n\t"strings"\n\t"time"''',
    '''\t"math/rand"\n\t"net"\n\t"strconv"\n\t"strings"\n\t"time"''',
)
replace(
    "common/tlsfragment/conn.go",
    '''\tfallbackDelay     time.Duration\n}''',
    '''\tfallbackDelay     time.Duration\n\tfragmentLengthMin  int\n\tfragmentLengthMax  int\n\tfragmentDelayMin   time.Duration\n\tfragmentDelayMax   time.Duration\n}''',
)
replace(
    "common/tlsfragment/conn.go",
    '''func NewConn(conn net.Conn, ctx context.Context, splitPacket bool, splitRecord bool, fallbackDelay time.Duration) *Conn {\n\tif fallbackDelay == 0 {\n\t\tfallbackDelay = C.TLSFragmentFallbackDelay\n\t}\n\ttcpConn, _ := N.UnwrapReader(conn).(*net.TCPConn)\n\treturn &Conn{\n\t\tConn:          conn,\n\t\ttcpConn:       tcpConn,\n\t\tctx:           ctx,\n\t\tsplitPacket:   splitPacket,\n\t\tsplitRecord:   splitRecord,\n\t\tfallbackDelay: fallbackDelay,\n\t}\n}''',
    '''func NewConn(conn net.Conn, ctx context.Context, splitPacket bool, splitRecord bool, fallbackDelay time.Duration, fragmentLength string, fragmentInterval string) *Conn {\n\tif fallbackDelay == 0 {\n\t\tfallbackDelay = C.TLSFragmentFallbackDelay\n\t}\n\tlengthMin, lengthMax := parseIntRange(fragmentLength, 0, 0)\n\tdelayMin, delayMax := parseDurationRange(fragmentInterval)\n\ttcpConn, _ := N.UnwrapReader(conn).(*net.TCPConn)\n\treturn &Conn{\n\t\tConn:              conn,\n\t\ttcpConn:           tcpConn,\n\t\tctx:               ctx,\n\t\tsplitPacket:       splitPacket,\n\t\tsplitRecord:       splitRecord,\n\t\tfallbackDelay:     fallbackDelay,\n\t\tfragmentLengthMin: lengthMin,\n\t\tfragmentLengthMax: lengthMax,\n\t\tfragmentDelayMin:  delayMin,\n\t\tfragmentDelayMax:  delayMax,\n\t}\n}\n\nfunc parseIntRange(value string, defaultMin int, defaultMax int) (int, int) {\n\tvalue = strings.TrimSpace(value)\n\tif value == "" {\n\t\treturn defaultMin, defaultMax\n\t}\n\tparts := strings.SplitN(value, "-", 2)\n\tminValue, err := strconv.Atoi(strings.TrimSpace(parts[0]))\n\tif err != nil || minValue < 1 {\n\t\treturn defaultMin, defaultMax\n\t}\n\tmaxValue := minValue\n\tif len(parts) == 2 {\n\t\tmaxValue, err = strconv.Atoi(strings.TrimSpace(parts[1]))\n\t\tif err != nil || maxValue < minValue {\n\t\t\treturn defaultMin, defaultMax\n\t\t}\n\t}\n\treturn minValue, maxValue\n}\n\nfunc parseDurationRange(value string) (time.Duration, time.Duration) {\n\tvalue = strings.TrimSpace(value)\n\tif value == "" {\n\t\treturn 0, 0\n\t}\n\tparts := strings.SplitN(value, "-", 2)\n\tparse := func(raw string) (time.Duration, error) {\n\t\traw = strings.TrimSpace(raw)\n\t\tif raw == "0" {\n\t\t\treturn 0, nil\n\t\t}\n\t\treturn time.ParseDuration(raw)\n\t}\n\tminValue, err := parse(parts[0])\n\tif err != nil || minValue < 0 {\n\t\treturn 0, 0\n\t}\n\tmaxValue := minValue\n\tif len(parts) == 2 {\n\t\tmaxValue, err = parse(parts[1])\n\t\tif err != nil || maxValue < minValue {\n\t\t\treturn 0, 0\n\t\t}\n\t}\n\treturn minValue, maxValue\n}\n\nfunc randomInt(minValue int, maxValue int) int {\n\tif maxValue <= minValue {\n\t\treturn minValue\n\t}\n\treturn minValue + rand.Intn(maxValue-minValue+1)\n}\n\nfunc randomDuration(minValue time.Duration, maxValue time.Duration) time.Duration {\n\tif maxValue <= minValue {\n\t\treturn minValue\n\t}\n\treturn minValue + time.Duration(rand.Int63n(int64(maxValue-minValue)+1))\n}''',
)
replace(
    "common/tlsfragment/conn.go",
    '''\t\t\tvar splitIndexes []int\n\t\t\tfor i, split := range splits {\n\t\t\t\tsplitAt := rand.Intn(len(split))\n\t\t\t\tsplitIndexes = append(splitIndexes, currentIndex+splitAt)\n\t\t\t\tcurrentIndex += len(split)\n\t\t\t\tif i != len(splits)-1 {\n\t\t\t\t\tcurrentIndex++\n\t\t\t\t}\n\t\t\t}''',
    '''\t\t\tvar splitIndexes []int\n\t\t\tif c.fragmentLengthMin > 0 {\n\t\t\t\tfor splitAt := randomInt(c.fragmentLengthMin, c.fragmentLengthMax); splitAt < len(b); splitAt += randomInt(c.fragmentLengthMin, c.fragmentLengthMax) {\n\t\t\t\t\tsplitIndexes = append(splitIndexes, splitAt)\n\t\t\t\t}\n\t\t\t} else {\n\t\t\t\tfor i, split := range splits {\n\t\t\t\t\tsplitAt := rand.Intn(len(split))\n\t\t\t\t\tsplitIndexes = append(splitIndexes, currentIndex+splitAt)\n\t\t\t\t\tcurrentIndex += len(split)\n\t\t\t\t\tif i != len(splits)-1 {\n\t\t\t\t\t\tcurrentIndex++\n\t\t\t\t\t}\n\t\t\t\t}\n\t\t\t}\n\t\t\tif len(splitIndexes) == 0 {\n\t\t\t\treturn c.Conn.Write(b)\n\t\t\t}''',
)
replace(
    "common/tlsfragment/conn.go",
    '''\t\t\t\t\tif i != len(splitIndexes) {\n\t\t\t\t\t\ttime.Sleep(c.fallbackDelay)\n\t\t\t\t\t}''',
    '''\t\t\t\t\tif i != len(splitIndexes) {\n\t\t\t\t\t\tdelay := randomDuration(c.fragmentDelayMin, c.fragmentDelayMax)\n\t\t\t\t\t\tif delay == 0 {\n\t\t\t\t\t\t\tdelay = c.fallbackDelay\n\t\t\t\t\t\t}\n\t\t\t\t\t\ttime.Sleep(delay)\n\t\t\t\t\t}''',
)

print("Applied DragonVPN configurable TLS fragmentation patch to", ROOT)
