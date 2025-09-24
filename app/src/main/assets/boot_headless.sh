#!/system/bin/sh
PACKAGE="com.mcity.palm.monitor"
SERVICE_COMPONENT="${PACKAGE}/.HeadlessService"
LOG="/data/local/shared/boot_headless.log"

mkdir -p /data/local/shared
echo "$(date) boot_headless.sh started" >> "$LOG"

# 等待系统完全启动
while [ "$(getprop sys.boot_completed)" != "1" ]; do
  sleep 1
done
echo "$(date) sys.boot_completed=1" >> "$LOG"

# 守护主循环
while true; do
  # 检查 Service 是否在运行（dumpsys activity services）
  RUNNING=$(dumpsys activity services "$SERVICE_COMPONENT" 2>/dev/null | grep -i "Running" || true)
  if [ -z "$RUNNING" ]; then
    echo "$(date) Service not running. Starting..." >> "$LOG"
    # 尝试以前台服务方式启动（Android O+）
    am start-foreground-service -n "$SERVICE_COMPONENT" >> "$LOG" 2>&1 || am startservice -n "$SERVICE_COMPONENT" >> "$LOG" 2>&1
    sleep 2
  fi

  # 轮询间隔
  sleep 5
done
