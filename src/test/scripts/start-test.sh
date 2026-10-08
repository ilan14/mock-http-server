#!/usr/bin/env bash
set -euo pipefail

PROJECT_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")/../../.." && pwd)"
TEST_DIR="$(mktemp -d)"
trap 'rm -rf -- "$TEST_DIR"' EXIT
mkdir -p "$TEST_DIR/project with spaces" "$TEST_DIR/bin"
cp "$PROJECT_DIR/start.sh" "$TEST_DIR/project with spaces/start.sh"

# 用模拟构建/Java 命令验证调用与参数，不启动实际服务。
cat > "$TEST_DIR/bin/mvn" <<'STUB'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$@" > build-args
mkdir -p target
touch target/mock.jar
STUB
cat > "$TEST_DIR/bin/java" <<'STUB'
#!/usr/bin/env bash
set -eu
printf '%s\n' "$@" > java-args
STUB
chmod +x "$TEST_DIR/bin/mvn" "$TEST_DIR/bin/java"
export PATH="$TEST_DIR/bin:$PATH"
cd "$TEST_DIR"
bash 'project with spaces/start.sh' > output
cd 'project with spaces'
printf '%s\n' -B -ntp -DskipTests clean package > expected-build
cmp expected-build build-args
# 后台命令已创建，但文件写入可能稍晚完成，有限等待输出完成。
for ((i=0; i<100; i++)); do
  if [[ -f java-args ]] && [[ $(wc -l < java-args) -eq 5 ]]; then
    break
  fi
  sleep 0.01
done
printf '%s\n' -Dfile.encoding=UTF-8 -Xms256m -Xmx512m -jar target/mock.jar > expected-java
cmp expected-java java-args
test -s logs/app.pid
test -f logs/start.log

# 存在 Wrapper 时应优先使用它。
cp "$TEST_DIR/bin/mvn" mvnw
chmod +x mvnw
cat > "$TEST_DIR/bin/mvn" <<'STUB'
#!/usr/bin/env bash
exit 99
STUB
bash start.sh > "$TEST_DIR/wrapper-output"
echo "start.sh tests passed"
