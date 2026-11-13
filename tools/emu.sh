#!/usr/bin/env bash
# Starts and stops the Pleos emulators, on Linux. Uses the hmg-pleos conda environment when it exists.
#   tools/emu.sh boot 1|2 [extra emulator flags]   Boots and waits until Android is ready.
#   tools/emu.sh stop 1|2                          Syncs storage and shuts down.
#   tools/emu.sh xvfb                              Starts a private virtual display, for machines without a screen.
# Vehicle 1 is emulator-5554 with Virtual Console on 8088. Vehicle 2 is emulator-5556 on 8089.
#
# Windowed mode is required for sound: with -no-window the emulator runs its headless binary,
# which cannot connect to PulseAudio. Set DISPLAY to a real screen, or use the xvfb command.
#
# emulator and adb must be on PATH. The AVDs are Pleos_Veh1 and Pleos_Veh2 unless AVD_1 and AVD_2 name others.
# EMU_GPU picks the renderer: swiftshader_indirect (default, no GPU needed) or host.
if command -v conda >/dev/null && conda env list | grep -q '^hmg-pleos '; then
  source "$(conda info --base)/etc/profile.d/conda.sh"
  conda activate hmg-pleos || exit 1
fi
for tool in emulator adb; do
  command -v "$tool" >/dev/null || { echo "$tool is not on PATH. Add the Android SDK's emulator and platform-tools folders."; exit 1; }
done

VIRTUAL_DISPLAY=:77
LOG_DIR="${TMPDIR:-/tmp}/parkrelay-emu"
mkdir -p "$LOG_DIR"

command=$1
vehicle=${2:-1}
port=$((5552 + vehicle * 2))
console=$((8087 + vehicle))
avd_variable="AVD_$vehicle"
avd="${!avd_variable:-Pleos_Veh$vehicle}"
serial="emulator-$port"

case "$command" in
  xvfb)
    if ! DISPLAY=$VIRTUAL_DISPLAY xdpyinfo >/dev/null 2>&1; then
      nohup setsid Xvfb "$VIRTUAL_DISPLAY" -screen 0 2800x1700x24 -ac +extension GLX +render -noreset >"$LOG_DIR/xvfb.log" 2>&1 </dev/null &
    fi
    echo "DISPLAY=$VIRTUAL_DISPLAY"
    ;;
  boot)
    shift 2
    export DISPLAY=${DISPLAY:-$VIRTUAL_DISPLAY}
    if pgrep -f -- "-avd $avd " >/dev/null; then
      echo "$avd is already running"
    else
      # On the virtual display both cars sit side by side at half size, so one remote view shows the
      # whole two-car scene. The emulator reads its window from this file at start and saves it at exit.
      window_ini="${ANDROID_AVD_HOME:-$HOME/.android/avd}/$avd.avd/emulator-user.ini"
      if [ "$DISPLAY" = "$VIRTUAL_DISPLAY" ] && [ -f "$window_ini" ]; then
        sed -i -e "s/^window.x = .*/window.x = $((40 + (vehicle - 1) * 1380))/" -e "s/^window.y = .*/window.y = 60/" \
          -e "s/^window.scale = .*/window.scale = 0.500000/" "$window_ini"
      fi
      # The last run's log says why it ended, so it is kept. Its exit status and time are the last lines.
      [ -f "$LOG_DIR/$avd.log" ] && mv "$LOG_DIR/$avd.log" "$LOG_DIR/$avd.$(date +%H%M%S).log"
      nohup setsid bash -c 'emulator "$@"; code=$?; echo "$(date +%T) emulator exited with status $code"' _ \
        -avd "$avd" -port "$port" -no-snapshot -no-boot-anim -show-kernel \
        -gpu "${EMU_GPU:-swiftshader_indirect}" -allow-host-audio "$@" >"$LOG_DIR/$avd.log" 2>&1 </dev/null &
    fi
    start=$SECONDS
    until [ "$(adb -s "$serial" shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; do
      if [ $((SECONDS - start)) -gt 280 ]; then
        echo "boot timeout for $avd"; tail -n 5 "$LOG_DIR/$avd.log"; exit 1
      fi
      sleep 3
    done
    adb -s "$serial" forward "tcp:$console" tcp:8088 >/dev/null
    echo "$avd ready as $serial after $((SECONDS - start))s, Virtual Console on port $console"
    ;;
  stop)
    adb -s "$serial" shell sync 2>/dev/null
    adb -s "$serial" emu kill >/dev/null 2>&1
    for _ in $(seq 1 30); do
      pgrep -f -- "-avd $avd " >/dev/null || break
      sleep 1
    done
    pgrep -f -- "-avd $avd " >/dev/null && echo "$avd is still running" || echo "$avd stopped"
    ;;
  *)
    sed -n '2,12p' "$0"; exit 2
    ;;
esac
