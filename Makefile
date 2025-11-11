.PHONY: help build-release build-internal clean deploy-internal deploy-playstore test lint

# Default target
help:
	@echo "Kahf Browser - Android Build & Release Makefile"
	@echo ""
	@echo "Available targets:"
	@echo "  build-release        - Build Play Store release APK"
	@echo "  build-internal       - Build internal release APK"
	@echo "  build-bundle         - Build Play Store release bundle (AAB)"
	@echo "  deploy-internal      - Build and deploy to Play Store internal testing track"
	@echo "  deploy-playstore     - Build and deploy to Play Store production (0.1% rollout)"
	@echo "  test                 - Run unit tests"
	@echo "  lint                 - Run lint checks"
	@echo "  clean                - Clean build artifacts"
	@echo ""
	@echo "Environment variables needed for deployment:"
	@echo "  - Signing keys must be configured at ~/jenkins_static/com.duckduckgo.mobile.android/"
	@echo "  - Google Play API credentials at ~/jenkins_static/com.duckduckgo.mobile.android/api.json"

# Build Play Store release APK
build-release:
	@echo "Building Play Store release APK..."
	./gradlew assemblePlayRelease
	@echo "✅ Build complete! APK location:"
	@find app/build/outputs/apk/play/release/ -name "*.apk" -type f

# Build internal release APK
build-internal:
	@echo "Building internal release APK..."
	./gradlew assembleInternalRelease
	@echo "✅ Build complete! APK location:"
	@find app/build/outputs/apk/internal/release/ -name "*.apk" -type f

# Build Play Store release bundle (AAB)
build-bundle:
	@echo "Building Play Store release bundle (AAB)..."
	./gradlew bundlePlayRelease
	@echo "✅ Build complete! Bundle location:"
	@find app/build/outputs/bundle/playRelease/ -name "*.aab" -type f

# Deploy to Play Store internal testing track (dogfood)
deploy-internal: build-release
	@echo "Deploying to Play Store internal testing track..."
	@APK_PATH=$$(find app/build/outputs/apk/play/release/ -name "*.apk" -type f | head -n 1); \
	if [ -z "$$APK_PATH" ]; then \
		echo "❌ Error: APK not found. Build failed?"; \
		exit 1; \
	fi; \
	echo "Using APK: $$APK_PATH"; \
	bundle exec fastlane deploy_dogfood apk_path:$$APK_PATH
	@echo "✅ Deployed to internal testing track!"

# Deploy to Play Store production with 0.1% rollout
deploy-playstore: build-release
	@echo "⚠️  WARNING: This will deploy to Play Store PRODUCTION with 0.1% rollout"
	@echo "Press Ctrl+C to cancel, or wait 5 seconds to continue..."
	@sleep 5
	@echo "Deploying to Play Store production..."
	bundle exec fastlane deploy_playstore
	@echo "✅ Deployed to production!"

# Run unit tests
test:
	@echo "Running unit tests..."
	./gradlew test
	@echo "✅ Tests complete!"

# Run lint checks
lint:
	@echo "Running lint checks..."
	./gradlew lint
	@echo "✅ Lint complete!"

# Clean build artifacts
clean:
	@echo "Cleaning build artifacts..."
	./gradlew clean
	@echo "✅ Clean complete!"
