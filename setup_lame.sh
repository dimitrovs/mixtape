#!/bin/bash
#
# Downloads and sets up the LAME MP3 encoder source code for the Android NDK build.
# Run this once before building the project.
#
# LAME is licensed under LGPL 2.1 — bundling as a shared library in an app is permitted.
#

set -euo pipefail

LAME_VERSION="3.100"
LAME_URL="https://sourceforge.net/projects/lame/files/lame/${LAME_VERSION}/lame-${LAME_VERSION}.tar.gz/download"
DEST_DIR="app/src/main/cpp/lame"

# Check if already set up
if [ -f "${DEST_DIR}/include/lame.h" ]; then
    echo "LAME source already present at ${DEST_DIR}"
    exit 0
fi

echo "Downloading LAME ${LAME_VERSION}..."
TEMP_DIR=$(mktemp -d)
curl -L -o "${TEMP_DIR}/lame.tar.gz" "${LAME_URL}"

echo "Extracting..."
tar xzf "${TEMP_DIR}/lame.tar.gz" -C "${TEMP_DIR}"

echo "Copying source files to ${DEST_DIR}..."
mkdir -p "${DEST_DIR}"

# Copy the required directories
cp -r "${TEMP_DIR}/lame-${LAME_VERSION}/include" "${DEST_DIR}/"
cp -r "${TEMP_DIR}/lame-${LAME_VERSION}/libmp3lame" "${DEST_DIR}/"

# Create config.h for Android
cat > "${DEST_DIR}/config.h" << 'CONFIGEOF'
#ifndef LAME_CONFIG_H
#define LAME_CONFIG_H

#define STDC_HEADERS 1
#define HAVE_STDINT_H 1
#define HAVE_STDLIB_H 1
#define HAVE_STRING_H 1
#define HAVE_LIMITS_H 1
#define HAVE_ERRNO_H 1
#define HAVE_FCNTL_H 1

#define SIZEOF_SHORT 2
#define SIZEOF_INT 4
#define SIZEOF_LONG 4
#define SIZEOF_LONG_LONG 8
#define SIZEOF_DOUBLE 8

/* Use IEEE754 hack for speed */
#define TAKEHIRO_IEEE754_HACK 1
#define USE_FAST_LOG 1

#define HAVE_MPGLIB 1
#define DECODE_ON_THE_FLY 1

#endif /* LAME_CONFIG_H */
CONFIGEOF

# Clean up
rm -rf "${TEMP_DIR}"

echo ""
echo "LAME ${LAME_VERSION} source is ready at ${DEST_DIR}"
echo "You can now build the project with: ./gradlew assembleDebug"
