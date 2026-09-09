# GPic Android — recurring dev commands.
# Secrets stay in local.properties (git-ignored); this file contains none.
#
#   make setup        # JDK note + SDK tools + packages + local.properties template
#   make keystore     # self-signed keystore for local release builds (git-ignored)
#   make debug        # fastest verification build (5 ABI APKs)
#   make release      # signed, versioned release APKs (needs keystore, see setup)
#   make install      # adb install arm64 debug APK (ABI=... to override)
#   make publish      # build + commit + push + tag + GitHub release (V=1.4 CODE=11 NOTES="...")
#   make emulator     # create + boot headless AVD, wait for adb

ANDROID_HOME ?= $(HOME)/android-sdk
export ANDROID_HOME
SDKMANAGER := $(ANDROID_HOME)/cmdline-tools/latest/bin/sdkmanager
ADB := $(ANDROID_HOME)/platform-tools/adb
APKS := app/build/outputs/apk/versioned
ABI ?= arm64-v8a

# Overridable per invocation, e.g. `make publish V=1.4 CODE=11 NOTES="..."`
V ?=
CODE ?=
NOTES ?=
NOTES_FILE ?=

.PHONY: help
help:
	@echo "Targets: setup keystore debug release install verify-sig publish reupload emulator"
	@echo "         auth-log"

# ---------------------------------------------------------------- environment

.PHONY: env
env:
	java -version 2>&1
	@echo "ANDROID_HOME=$(ANDROID_HOME)"
	@test -d "$(ANDROID_HOME)/platforms" && ls "$(ANDROID_HOME)/platforms" || echo "no platforms — run: make setup"

# JDK 21 + cmdline-tools (edgedl mirror; dl.google.com 404s from some networks)
# + platform-37.0 (note the .0 suffix) + build-tools + platform-tools,
# then a local.properties template (fill in your own keys afterwards).
.PHONY: setup
setup:
	sudo apt-get update -qq && sudo apt-get install -y -qq openjdk-21-jdk-headless unzip
	mkdir -p /tmp/opencode $(ANDROID_HOME)/cmdline-tools
	curl -sSLo /tmp/opencode/cmdtools.zip https://edgedl.me.gvt1.com/android/repository/commandlinetools-linux-15859902_latest.zip
	unzip -q -o /tmp/opencode/cmdtools.zip -d $(ANDROID_HOME)/cmdline-tools
	mv $(ANDROID_HOME)/cmdline-tools/cmdline-tools $(ANDROID_HOME)/cmdline-tools/latest
	yes | $(SDKMANAGER) --licenses > /dev/null
	$(SDKMANAGER) "platform-tools" "platforms;android-37.0" "build-tools;37.0.0"
	@test -f local.properties || printf 'sdk.dir=%s\nkeystore.path=gpic.keystore\nkeystore.password=gpic123\nkeystore.alias=gpic\n' "$(ANDROID_HOME)" > local.properties
	@echo "local.properties ready (git-ignored). Run 'make keystore' for release signing."

# Self-signed keystore for local release builds (git-ignored via *.keystore).
.PHONY: keystore
keystore:
	keytool -genkeypair -keystore gpic.keystore -alias gpic -keyalg RSA -keysize 2048 -validity 10000 -storepass gpic123 -keypass gpic123 -dname "CN=GPic, OU=Dev, O=GPic, L=Internet, ST=NA, C=US"

# ------------------------------------------------------------------- build

.PHONY: debug
debug:
	./gradlew assembleDebug --no-daemon -q
	@ls app/build/outputs/apk/debug/

.PHONY: release
release:
	./gradlew versionApks --no-daemon
	@ls $(APKS)/

.PHONY: install
install:
	$(ADB) install -r app/build/outputs/apk/debug/app-$(ABI)-debug.apk

# ------------------------------------------------------------------ verify

.PHONY: verify-sig
verify-sig:
	$(ANDROID_HOME)/build-tools/37.0.0/apksigner verify --print-certs $(APKS)/GPic-$(V)-$(ABI).apk

# ----------------------------------------------------------------- publish
# Full convention: versionApks -> commit ("...; bump to vX") -> push ->
# tag vX -> push tag -> gh release with all 5 APKs.
#   make publish V=1.4 CODE=11 NOTES="**Fixes:** ..."
# or NOTES_FILE=/tmp/notes.md to avoid shell-quoting pain.

.PHONY: publish
publish:
	@test -n "$(V)" -a -n "$(CODE)" || (echo "usage: make publish V=1.4 CODE=11 NOTES=\"...\""; exit 1)
	sed -i 's/versionCode = .*/versionCode = $(CODE)/; s/versionName = ".*"/versionName = "$(V)"/' app/build.gradle.kts
	sed -i 's/^val releaseVersion = ".*"/val releaseVersion = "$(V)"/' app/build.gradle.kts
	$(MAKE) release
	git add -A && git commit -m "Release $(V) ($(CODE))" && git push origin main
	git tag v$(V) && git push origin v$(V)
	@if [ -n "$(NOTES_FILE)" ]; then \
		gh release create v$(V) --title "GPic v$(V)" --notes-file "$(NOTES_FILE)" $(APKS)/GPic-$(V)-*.apk; \
	else \
		gh release create v$(V) --title "GPic v$(V)" --notes "$(NOTES)" $(APKS)/GPic-$(V)-*.apk; \
	fi
	gh release view v$(V) --json assets --jq '.assets[] | "\(.name)  \(.size) bytes"'

# Replace already-uploaded assets (e.g. rebuilt APKs):
#   make reupload V=1.3.5
.PHONY: reupload
reupload:
	@test -n "$(V)" || (echo "usage: make reupload V=1.3.5"; exit 1)
	cd $(APKS) && gh release upload v$(V) --clobber GPic-$(V)-*.apk

# ---------------------------------------------------------------- emulator

.PHONY: emulator-deps
emulator-deps:
	$(SDKMANAGER) "emulator" "system-images;android-37.0;google_apis;x86_64"
	echo "no" | $(ANDROID_HOME)/cmdline-tools/latest/bin/avdmanager create avd -n gpic -k "system-images;android-37.0;google_apis;x86_64" --device "pixel_7" --force

# Headless boot (no KVM here: TCG cold boot takes ~10 min first time).
.PHONY: emulator
emulator:
	nohup $(ANDROID_HOME)/emulator/emulator -avd gpic -no-window -no-audio -no-boot-anim -gpu swiftshader_indirect -accel off > /tmp/emulator.log 2>&1 &
	for i in $$(seq 1 60); do \
		s=$$($(ADB) devices 2>/dev/null | grep emulator | awk '{print $$2}'); \
		echo "attempt $$i: $$s"; \
		[ "$$s" = device ] && break; sleep 10; \
	done
	$(ADB) devices

# ------------------------------------------------------------ auth helper
# Capture the Google Photos master-token line (must contain photos.native,
# NOT userinfo.profile). Open Google Photos on the phone while this runs,
# then paste the FULL androidId=... line into GPic → Auth → Save.

.PHONY: auth-log
auth-log:
	$(ADB) logcat -c
	$(ADB) logcat | grep -i "photos.native"
