#!/usr/bin/env bash
# The cluster entry point: the arm64 emulator this playground needs, then the cluster, then the images.
set -euo pipefail

# The gateway workload runs `saiupadhyayula007/new-api-gateway:latest`, and Docker Hub publishes that
# image for `linux/arm64` only (one tag, one architecture: measured, issue #1056). A kind node shares
# the host kernel, so on an amd64 host the kubelet fails with
# `exec /cnb/process/web: exec format error` and the pod never reaches Ready -- no securityContext can
# fix that, because the image has no amd64 code to run.
#
# `binfmt_misc` is kernel-global and this registration uses the `F` flag, which copies the emulator
# into the kernel, so the kind node inherits it without anything being installed inside a node. It is
# a host-level prerequisite of the playground: registered here, before the cluster exists, and read
# back instead of trusted, because the alternative is a pod that fails an hour later in another script.
ARM64_REGISTRATION=/proc/sys/fs/binfmt_misc/qemu-aarch64

echo "===Starting Kind Cluster==="

if [ -e "$ARM64_REGISTRATION" ]; then
	echo "arm64 emulation is already registered"
else
	echo "Registering arm64 emulation, which the arm64-only gateway image needs on an amd64 host"
	if ! docker run --rm --privileged tonistiigi/binfmt:qemu-v10.2.3 --install arm64; then
		echo "could not register arm64 emulation: the api-gateway pod would fail with exec format error" >&2
		exit 1
	fi
	if [ ! -e "$ARM64_REGISTRATION" ]; then
		echo "arm64 emulation is still not registered after installing it, so the api-gateway pod cannot start" >&2
		exit 1
	fi
	echo "arm64 emulation registered"
fi

kind create cluster --name microservices --config kind-config.yaml

echo "Loading Docker Images into Kind Cluster"

chmod +x ./kind-load.sh
./kind-load.sh

echo "===Kind Cluster Started==="
