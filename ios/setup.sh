#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")"
for tool in swift protoc xcodegen; do
  command -v "$tool" >/dev/null || { echo "Missing required tool: $tool" >&2; exit 1; }
done

tools_dir="$PWD/.build/codegen"
mkdir -p "$tools_dir" FamilyBoard/Generated
build_plugin() {
  local name="$1" url="$2" version="$3" product="$4"
  if [ ! -d "$tools_dir/$name/.git" ]; then
    git clone --depth 1 --branch "$version" "$url" "$tools_dir/$name"
  fi
  swift build --package-path "$tools_dir/$name" -c release --product "$product" >&2
  swift build --package-path "$tools_dir/$name" -c release --show-bin-path
}

swift_bin="$(build_plugin swift-protobuf https://github.com/apple/swift-protobuf.git 1.31.1 protoc-gen-swift)"
grpc_bin="$(build_plugin grpc-swift-protobuf https://github.com/grpc/grpc-swift-protobuf.git 2.3.0 protoc-gen-grpc-swift-2)"
protoc -I ../proto \
  --plugin="protoc-gen-swift=$swift_bin/protoc-gen-swift" \
  --plugin="protoc-gen-grpc-swift-2=$grpc_bin/protoc-gen-grpc-swift-2" \
  --swift_out=FamilyBoard/Generated \
  --grpc-swift-2_out=FamilyBoard/Generated \
  --grpc-swift-2_opt=Client=true,Server=false \
  ../proto/familyboard/v1/familyboard.proto
xcodegen
