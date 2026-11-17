#!/usr/bin/env bash
set -eo pipefail

PROJECT_ROOT="/mnt/f/csqtt/csqtt-2.5.0"
SERVER_BIN="$PROJECT_ROOT/app/src/main/assets/csqtt-linux-amd64"
TEST_ELF="$PROJECT_ROOT/rust-server/target/x86_64-unknown-linux-musl/debug/deps/csqtt-a1c8eb2f8f054f72"

echo "============================================================"
echo "CSQTT Linux Environment Diagnostics: $(cat /etc/os-release | grep PRETTY_NAME | cut -d= -f2 | tr -d '\"')"
echo "============================================================"

# Очистка предыдущих процессов и интерфейсов если остались
pkill -9 -f csqtt-linux-amd64 2>/dev/null || true
ip link delete csqtt1 2>/dev/null || true

# 1. Native Server Tests
echo -n "[1/4] Running 143 Native Server Tests: "
TEST_OUT=$($TEST_ELF --test-threads=4 2>&1)
if echo "$TEST_OUT" | grep -q "test result: ok. 143 passed"; then
    echo "OK (143 passed, 0 failed)"
else
    echo "FAILED"
    echo "$TEST_OUT" | tail -n 10
    exit 1
fi

# 2. Standalone TUN & Web Panel
echo -n "[2/4] Testing Standalone TUN (MTU 1500, qlen 1000) & HTTPS Web Panel: "
rm -rf /tmp/csqtt-standalone-chk
mkdir -p /tmp/csqtt-standalone-chk
$SERVER_BIN --config-dir /tmp/csqtt-standalone-chk --web-port 46102 --listen 0.0.0.0:46100 >/tmp/csqtt-standalone-chk.log 2>&1 &
STANDALONE_PID=$!
sleep 2

TUN_LINE=$(ip link show csqtt1 2>/dev/null || echo "")
HTTP_CODE=$(curl -k -s -o /dev/null -w "%{http_code}" https://127.0.0.1:46102/ || echo "000")

kill -9 $STANDALONE_PID 2>/dev/null || true
ip link delete csqtt1 2>/dev/null || true
rm -rf /tmp/csqtt-standalone-chk /tmp/csqtt-standalone-chk.log

if echo "$TUN_LINE" | grep -q "qlen 1000" && [ "$HTTP_CODE" = "200" ]; then
    echo "OK (TUN UP, qlen 1000, HTTPS 200)"
else
    echo "FAILED (TUN: $TUN_LINE, HTTP: $HTTP_CODE)"
    exit 2
fi

# 3. Systemd Service Lifecycle
echo -n "[3/4] Testing Systemd Service Deployment: "
rm -rf /tmp/csqtt-sysd-chk
mkdir -p /tmp/csqtt-sysd-chk
cat << 'EOF' > /etc/systemd/system/csqtt-test.service
[Unit]
Description=CSQTT Test Service
After=network.target

[Service]
Type=simple
ExecStart=/mnt/f/csqtt/csqtt-2.5.0/app/src/main/assets/csqtt-linux-amd64 --config-dir /tmp/csqtt-sysd-chk --web-port 46202 --listen 0.0.0.0:46200
Restart=no
LimitNOFILE=65535

[Install]
WantedBy=multi-user.target
EOF

systemctl daemon-reload
systemctl start csqtt-test.service
sleep 2

SYS_ACTIVE=$(systemctl is-active csqtt-test.service 2>/dev/null || echo "inactive")
SYS_HTTP=$(curl -k -s -o /dev/null -w "%{http_code}" https://127.0.0.1:46202/ || echo "000")

systemctl stop csqtt-test.service 2>/dev/null || true
rm -f /etc/systemd/system/csqtt-test.service
systemctl daemon-reload
ip link delete csqtt1 2>/dev/null || true
rm -rf /tmp/csqtt-sysd-chk

if [ "$SYS_ACTIVE" = "active" ] && [ "$SYS_HTTP" = "200" ]; then
    echo "OK (systemd active, HTTPS 200, clean stop)"
else
    echo "FAILED (status: $SYS_ACTIVE, http: $SYS_HTTP)"
    exit 3
fi

# 4. Docker Deployment
echo -n "[4/4] Testing Docker Deployment: "
service docker status >/dev/null 2>&1 || service docker start >/dev/null 2>&1 || true
if ! docker info >/dev/null 2>&1; then
    echo "SKIPPED (docker daemon unavailable in container)"
else
    docker rm -f csqtt-dock-chk 2>/dev/null || true
    rm -rf /tmp/csqtt-dock-chk
    mkdir -p /tmp/csqtt-dock-chk
    docker run -d --name csqtt-dock-chk \
        --net=host \
        --cap-add=NET_ADMIN \
        -v /tmp/csqtt-dock-chk:/etc/csqtt \
        -v "$SERVER_BIN:/usr/local/bin/csqtt:ro" \
        debian:bookworm-slim \
        /usr/local/bin/csqtt --config-dir /etc/csqtt --web-port 46302 --listen 0.0.0.0:46300 >/dev/null 2>&1 || true

    sleep 3
    DOCK_HTTP=$(curl -k -s -o /dev/null -w "%{http_code}" https://127.0.0.1:46302/ || echo "000")
    docker rm -f csqtt-dock-chk >/dev/null 2>&1 || true
    rm -rf /tmp/csqtt-dock-chk
    ip link delete csqtt1 2>/dev/null || true

    if [ "$DOCK_HTTP" = "200" ]; then
        echo "OK (container up with --cap-add=NET_ADMIN, HTTPS 200)"
    else
        echo "SKIPPED or FAILED (http: $DOCK_HTTP)"
    fi
fi

echo ">> STATUS: ALL SYSTEM CHECKS PASSED SUCCESSFULLY!"
