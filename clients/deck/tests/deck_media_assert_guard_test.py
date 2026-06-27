#!/usr/bin/env python3
import pathlib
import re
import unittest


DECK_ROOT = pathlib.Path(__file__).resolve().parents[1]
MEDIA_ADAPTER_TEST = DECK_ROOT / "tests" / "deck_stream_media_adapters_test.cpp"
MEDIA_ADAPTER_SOURCE = DECK_ROOT / "src" / "stream" / "deck_stream_media_adapters.cpp"
DECK_MAIN_SOURCE = DECK_ROOT / "src" / "main.cpp"
FRONTEND_SMOKE_ROUTE = DECK_ROOT / "scripts" / "deck_frontend_smoke.py"
BACKEND_HEADER = DECK_ROOT / "src" / "backend" / "deck_backend_interfaces.h"
BACKEND_SOURCE = DECK_ROOT / "src" / "backend" / "deck_backend_interfaces.cpp"
BACKEND_TEST = DECK_ROOT / "tests" / "deck_backend_interfaces_test.cpp"


class DeckMediaAssertGuardTest(unittest.TestCase):
    def test_media_adapter_harness_has_no_runtime_assert_control_flow(self):
        source = MEDIA_ADAPTER_TEST.read_text(encoding="utf-8")
        runtime_asserts = []
        for line_number, line in enumerate(source.splitlines(), start=1):
            if re.search(r"(?<!static_)\bassert\s*\(", line):
                runtime_asserts.append(f"{line_number}: {line.strip()}")

        self.assertEqual(
            runtime_asserts,
            [],
            "runtime assert() compiles out in Release-ish builds; use require() checks instead:\n"
            + "\n".join(runtime_asserts),
        )

    def test_render_node_import_uses_current_opengl_context_not_private_qrhi_command_buffer(self):
        source = MEDIA_ADAPTER_SOURCE.read_text(encoding="utf-8")

        self.assertIn(
            "renderPresenterTexture(presenterResource_, rect(), projectionMatrix())",
            source,
            "render node must attempt shader composition from the QSG render call",
        )
        self.assertNotIn(
            "commandBuffer() == nullptr",
            source,
            "Deck VAAPI presentation should use the public current OpenGL/EGL render-thread context seam, not block on QRhi command buffer access",
        )

    def test_desktop_gl_shader_path_does_not_mix_gles_external_oes_fragment_with_desktop_vertex(self):
        source = MEDIA_ADAPTER_SOURCE.read_text(encoding="utf-8")

        self.assertIn(
            "if (!currentContextUsesOpenGles())",
            source,
            "single-layer ExternalOES presentation must fail closed on desktop OpenGL instead of linking a GLES fragment shader with a desktop vertex shader",
        )
        self.assertIn(
            "std::hex",
            source,
            "GL error diagnostics must render true hexadecimal values, not decimal values with a 0x prefix",
        )
    def test_product_preview_shell_wires_media_adapter_to_pipeline_without_missing_lease_fixture(self):
        source = DECK_MAIN_SOURCE.read_text(encoding="utf-8")

        self.assertIn(
            "DeckGuardedPreviewLifecycleGate productPreviewLifecycleGate(productPreviewProducer)",
            source,
            "Deck shell must wire product preview through the guarded lifecycle gate instead of exposing raw producer start control",
        )
        self.assertIn(
            "productPreviewLifecycleGate.attachProductPreviewPipeline(productPreviewPipeline)",
            source,
            "Deck shell must attach the decoded-frame pipeline through the lifecycle gate",
        )
        self.assertNotIn(
            "DeckVaapiFfmpegRenderer productPreviewRenderer",
            source,
            "Deck shell must not own the decoded-frame producer directly; the guarded stream-session seam owns it",
        )
        self.assertNotIn(
            "product-offline-preview-fixture-missing-lease",
            source,
            "Deck shell must not pump the old missing-lease product preview fixture",
        )
        self.assertNotIn(
            "productPreviewRenderer.setup(",
            source,
            "Deck shell must not start a local decode fixture or host stream from main.cpp",
        )
        self.assertNotIn(
            "productPreviewRenderer.start(",
            source,
            "Deck shell must only wire the decoded-frame sink; actual stream startup remains gated elsewhere",
        )
        self.assertNotIn(
            "productPreviewProducer.prepareNoNetwork(",
            source,
            "Deck shell main.cpp must not prepare the stream session directly; product lifecycle control belongs to the guarded gate",
        )
        self.assertNotIn(
            "productPreviewProducer.startNoNetwork(",
            source,
            "Deck shell main.cpp must not start even the no-network skeleton directly; product lifecycle control belongs to the guarded gate",
        )
        self.assertNotIn(
            "submitDecodeUnit(",
            source,
            "Deck shell must not submit decode units directly; decoded frames arrive through the guarded stream-session producer",
        )

    def test_guarded_preview_lifecycle_gate_keeps_network_start_source_unreachable(self):
        header = (DECK_ROOT / "src" / "stream" / "deck_stream_media_adapters.h").read_text(encoding="utf-8")
        source = MEDIA_ADAPTER_SOURCE.read_text(encoding="utf-8")

        self.assertIn("struct DeckGuardedPreviewLifecycleReport", header)
        self.assertIn("struct DeckOperatorStartAuthorizationSnapshot", header)
        self.assertIn("class DeckOperatorStartAuthorizationPolicy", header)
        self.assertIn("DeckOperatorStartAuthorizationMode::Blocked", source)
        self.assertIn("DeckOperatorStartAuthorizationMode::DryRunAuthorized", source)
        self.assertIn("DeckOperatorStartAuthorizationMode::StartAuthorized", source)
        self.assertIn("class DeckGuardedPreviewLifecycleGate", header)
        self.assertIn("armNoNetwork", header)
        self.assertIn("requestGuardedHostNetworkStart", header)
        self.assertIn("requestOperatorAuthorizedDryRun", header)
        self.assertIn("requestOperatorAuthorizedHostNetworkStart", header)
        self.assertIn("requestHostStartDryRunPreflight", header)
        self.assertIn("dryRunPreflightRequested", header)
        self.assertIn("hostStartBoundaryExplicit", header)
        self.assertIn("hostStartContractAuthorized", header)
        self.assertNotIn(
            "hostNetworkStartAuthorized",
            header + source,
            "operator start contract authorization must not be named like real host/network permission",
        )
        self.assertIn("operatorAuthorizationState", header)
        self.assertIn("host-network-start-blocked", source)
        self.assertIn("host-start-preflight-missing-host", source)
        self.assertIn("host-start-preflight-contract-blocked", source)
        self.assertIn("host-start-dry-run-preflight-authorized", source)
        self.assertIn("operator-dry-run-authorized", source)
        self.assertIn("operator-start-not-ready", source)
        self.assertIn("networkStartAllowed = false", header)
        self.assertIn("active-no-network", source)
        self.assertNotIn("LiStartConnection", source)
        self.assertNotIn("networkStartAllowed = true", header + source)

    def test_operator_start_contract_copy_does_not_label_contract_as_network_permission(self):
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")
        qml = (DECK_ROOT / "qml" / "Main.qml").read_text(encoding="utf-8")
        bridge_and_qml = main + "\n" + qml

        self.assertIn('model.insert("hostStartContractAuthorized"', main)
        self.assertIn("previewLifecycleReport.hostStartContractAuthorized", qml)
        self.assertIn("Start contract authorized:", qml)
        self.assertIn("Network start allowed:", qml)
        self.assertNotIn(
            "hostNetworkStartAuthorized",
            bridge_and_qml,
            "bridge/QML must expose operator contract authorization separately from networkStartAllowed",
        )
        for forbidden_copy in (
            "Network authorized",
            "Network allowed",
            "network authorized",
            "network allowed",
        ):
            self.assertNotIn(forbidden_copy, qml)

    def test_product_preview_lifecycle_control_surface_uses_guarded_bridge_only(self):
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")
        qml = (DECK_ROOT / "qml" / "Main.qml").read_text(encoding="utf-8")

        self.assertIn("class QtPreviewLifecycleBridge final : public QObject", main)
        self.assertIn("operatorAuthorization", main)
        self.assertIn("Q_INVOKABLE QVariantMap authorizeOperatorDryRun", main)
        self.assertIn("Q_INVOKABLE QVariantMap authorizeOperatorStart", main)
        self.assertIn("Q_INVOKABLE QVariantMap armNoNetworkPreview", main)
        self.assertIn("Q_INVOKABLE QVariantMap requestOperatorAuthorizedDryRun", main)
        self.assertIn("Q_INVOKABLE QVariantMap requestOperatorAuthorizedHostNetworkStart", main)
        self.assertIn("Q_INVOKABLE QVariantMap requestHostStartDryRunPreflight", main)
        self.assertIn("Q_INVOKABLE QVariantMap requestGuardedHostNetworkStart", main)
        self.assertIn("Q_INVOKABLE QVariantMap stopPreview", main)
        self.assertIn('setContextProperty("novaPreviewLifecycle"', main)
        self.assertIn("novaPreviewLifecycle.authorizeOperatorDryRun", qml)
        self.assertIn("novaPreviewLifecycle.authorizeOperatorStart", qml)
        self.assertIn("novaPreviewLifecycle.armNoNetworkPreview", qml)
        self.assertIn("novaPreviewLifecycle.requestOperatorAuthorizedDryRun", qml)
        self.assertIn("novaPreviewLifecycle.requestOperatorAuthorizedHostNetworkStart", qml)
        self.assertIn("novaPreviewLifecycle.requestHostStartDryRunPreflight", qml)
        self.assertIn("novaPreviewLifecycle.requestGuardedHostNetworkStart", qml)
        self.assertIn("novaPreviewLifecycle.stopPreview", qml)
        self.assertIn("Authorize dry-run", qml)
        self.assertIn("Authorize start contract", qml)
        self.assertIn("Dry-run contract", qml)
        self.assertIn("Host start preflight", qml)
        self.assertIn("Arm preview", qml)
        self.assertIn("Start blocked", qml)
        self.assertIn("Stop", qml)

        forbidden_qml_calls = (
            "prepareNoNetwork(",
            "startNoNetwork(",
            "submitDecodeUnit(",
            "LiStartConnection",
            "startHost",
            "startStream",
        )
        for forbidden in forbidden_qml_calls:
            self.assertNotIn(forbidden, qml)

        self.assertNotIn("productPreviewProducer.prepareNoNetwork(", main)
        self.assertNotIn("productPreviewProducer.startNoNetwork(", main)
        self.assertNotIn("productPreviewProducer.stop(", main)

    def test_product_preview_lifecycle_report_surfaces_selected_request_details_without_debug_wall(self):
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")
        qml = (DECK_ROOT / "qml" / "Main.qml").read_text(encoding="utf-8")

        for expected_bridge_field in (
            'model.insert("hostId"',
            'model.insert("hostDisplayName"',
            'model.insert("gameId"',
            'model.insert("gameTitle"',
            'model.insert("requestSummary"',
        ):
            self.assertIn(expected_bridge_field, main)

        for expected_visible_detail in (
            "previewLifecycleReport.hostDisplayName",
            "previewLifecycleReport.gameTitle",
        ):
            self.assertIn(expected_visible_detail, qml)

        self.assertIn("Selected:", qml)
        self.assertIn("networkStarted=", qml)
        self.assertNotIn("Lifecycle reason:", qml)
        self.assertNotIn("Lifecycle request:", qml)

    def test_product_preview_lifecycle_bridge_keeps_duplicate_arm_selection_details_consistent(self):
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")

        self.assertIn("displayNameForReport", main)
        self.assertIn("report.hostId", main)
        self.assertIn("report.gameId", main)
        self.assertIn('QStringLiteral("hostId")', main)
        self.assertIn('QStringLiteral("gameId")', main)

    def test_product_preview_lifecycle_report_omits_credentials_and_host_start_tokens(self):
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")
        qml = (DECK_ROOT / "qml" / "Main.qml").read_text(encoding="utf-8")
        lifecycle_surface = main[main.index("class QtPreviewLifecycleBridge"):] + "\n" + qml

        forbidden_report_tokens = (
            "credential",
            "pairing",
            "accessToken",
            "refreshToken",
            "authToken",
            "password",
            "secret",
            "startHost",
            "startStream",
            "LiStartConnection",
        )
        for forbidden in forbidden_report_tokens:
            self.assertNotIn(forbidden, lifecycle_surface)

    def test_frontend_smoke_route_stays_offline_and_redacts_private_deck_data(self):
        route = FRONTEND_SMOKE_ROUTE.read_text(encoding="utf-8")

        self.assertIn("--network=none", route)
        self.assertIn("build/deck-frontend-smoke-artifacts", route)
        self.assertIn("frontend-frame-capture.png", route)
        self.assertIn("environment-summary.txt", route)
        for forbidden in (
            "LiStartConnection",
            "HostStore",
            "pair" + "ing",
            "credential",
            "accessToken",
            "refreshToken",
            "authToken",
            "password",
            "10.0.0.",
        ):
            self.assertNotIn(forbidden, route)

    def test_frontend_smoke_capture_is_app_owned_not_desktop_scraping(self):
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")

        self.assertIn("--frontend-smoke-exit-after-ms", main)
        self.assertIn("--frontend-smoke-capture", main)
        self.assertIn("grabWindow", main)
        self.assertIn("frontendSmokeCapturePath", main)
        self.assertNotIn("grim ", main)
        self.assertNotIn("spectacle", main)

    def test_backend_preflight_interfaces_exist_without_raw_backend_power_in_qml_or_main(self):
        header = BACKEND_HEADER.read_text(encoding="utf-8")
        source = BACKEND_SOURCE.read_text(encoding="utf-8")
        test = BACKEND_TEST.read_text(encoding="utf-8")
        main = DECK_MAIN_SOURCE.read_text(encoding="utf-8")
        qml = (DECK_ROOT / "qml" / "Main.qml").read_text(encoding="utf-8")

        for required_symbol in (
            "class DeckHostRepository",
            "class DeckFakeHostRepository",
            "class DeckCredentialStore",
            "class DeckLaunchPreflightService",
            "class DeckStreamSessionCoordinator",
            "class DeckDiagnosticsModel",
            "class DeckLabGate",
            "DeckPublicPreflightPreview",
            "DeckPublicDiagnosticsPreview",
            "requestDeckBackendPreflightPreview",
            "requestDeckBackendDiagnosticsPreview",
            "FixtureOnly",
            "MissingHost",
            "PairingRequired",
            "CertMismatch",
            "AuthRejected",
            "LibraryUnavailable",
            "SessionOwnedByAnotherClient",
            "RendererUnavailable",
            "AudioUnavailable",
            "InputUnavailable",
            "LabGateDisabled",
        ):
            self.assertIn(required_symbol, header + "\n" + source + "\n" + test)

        self.assertIn("class QtBackendPreviewBridge", main)
        self.assertIn("requestBackendPreflightPreview", main + "\n" + qml)
        self.assertIn("requestBackendDiagnosticsPreview", main + "\n" + qml)
        self.assertIn("novaBackendPreview", main + "\n" + qml)
        self.assertIn("--frontend-smoke-backend-dto-interactions", main)
        self.assertIn("backend-dto-interaction-smoke", main)
        self.assertIn("runBackendDtoPreviewInteractionSmoke", qml)
        self.assertIn("backendPreflightDtoPreviewButton.clicked()", qml)
        self.assertIn("backendDiagnosticsDtoPreviewButton.clicked()", qml)
        self.assertIn("backend-preflight-dto-preview", qml)
        self.assertIn("backend-diagnostics-dto-preview", qml)

        forbidden_qml_power = (
            "DeckCredentialStore",
            "DeckStreamSessionCoordinator",
            "LiStartConnection",
            "MoonBridge",
            "startConnection",
            "rawTokenForBackendOnly",
            "rawPrivateKeyForBackendOnly",
            "rawEndpointForBackendOnly",
        )
        for forbidden in forbidden_qml_power:
            self.assertNotIn(forbidden, qml)

        forbidden_main_raw_power = (
            "LiStartConnection",
            "MoonBridge",
            "startConnection",
            "rawTokenForBackendOnly",
            "rawPrivateKeyForBackendOnly",
            "rawEndpointForBackendOnly",
            "backendEndpointForTest",
            "upsertManualHostForTest",
        )
        for forbidden in forbidden_main_raw_power:
            self.assertNotIn(forbidden, main)

    def test_backend_public_copy_and_diagnostics_have_privacy_guard_cases(self):
        test = BACKEND_TEST.read_text(encoding="utf-8")

        for required_forbidden_probe in (
            "token-super-secret",
            "BEGIN CERTIFICATE",
            "BEGIN PRIVATE KEY",
            "https://10.0.0.42:47989/launch?token=super-secret",
            "10.0.0.42",
            "192.168.",
            "private-hostname.local",
        ):
            self.assertIn(required_forbidden_probe, test)

        self.assertIn("assertDiagnosticsAndPreflightCopyArePrivate", test)
        self.assertIn("copy.find(forbiddenToken) == std::string::npos", test)

    def test_raw_start_symbols_are_source_allowlisted_away_from_ui_and_stream_core(self):
        allowed_paths = {
            BACKEND_HEADER,
            BACKEND_SOURCE,
            BACKEND_TEST,
            pathlib.Path(__file__).resolve(),
        }
        forbidden_symbols = (
            "LiStartConnection",
            "MoonBridge",
            "startConnection",
            "rawTokenForBackendOnly",
            "rawPrivateKeyForBackendOnly",
            "rawEndpointForBackendOnly",
        )
        offenders = []
        for source_path in list((DECK_ROOT / "src").rglob("*.cpp")) + list((DECK_ROOT / "src").rglob("*.h")) + list((DECK_ROOT / "qml").rglob("*.qml")) + list((DECK_ROOT / "tests").rglob("*.cpp")):
            if source_path in allowed_paths:
                continue
            source = source_path.read_text(encoding="utf-8")
            for symbol in forbidden_symbols:
                if symbol in source:
                    offenders.append(f"{source_path.relative_to(DECK_ROOT)}: {symbol}")
        self.assertEqual(
            offenders,
            [],
            "raw backend/start symbols must stay in backend seams or backend test seams only:\n" + "\n".join(offenders),
        )


if __name__ == "__main__":
    unittest.main()
