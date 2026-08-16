import XCTest
@testable import HermesMobile

@MainActor
final class ChatModelTests: XCTestCase {
    func testSendAndStopUseRuntimeSessionIdentity() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.send(text: "hello")
        try await model.stop()

        let requests = await socket.requests
        XCTAssertEqual(requests.map(\.method), [GatewayMethod.promptSubmit, GatewayMethod.sessionInterrupt])
        XCTAssertEqual(requests[0].params?["session_id"], .string("runtime-1"))
        XCTAssertEqual(requests[1].params?["session_id"], .string("runtime-1"))
    }

    func testCurrentSessionTitleUsesRuntimeIdentity() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.renameCurrentSession("Roadmap")

        let requests = await socket.requests
        XCTAssertEqual(requests.map(\.method), [GatewayMethod.sessionTitle])
        XCTAssertEqual(requests[0].params?["session_id"], .string("runtime-1"))
    }

    func testInjectedArchiveStoreCanHideSessionWithoutSocketTraffic() async throws {
        let socket = RecordingSocket()
        let archive = InMemorySessionArchiveStore()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport, archiveStore: archive)

        try await model.connect()
        try await model.setArchived(true, storedSessionID: "stored-1")

        let archived = await archive.isArchived("stored-1")
        let requests = await socket.requests
        XCTAssertTrue(archived)
        XCTAssertTrue(requests.isEmpty)
    }

    func testRemoteArchiveUsesDurableSessionMutationClient() async throws {
        let socket = RecordingSocket()
        let archive = RecordingSessionMutationClient()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(
            transport: transport,
            sessionMutationClient: archive,
            sessions: [SessionSummary(storedID: "stored-1")]
        )

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.setArchived(true, storedSessionID: "stored-1")

        let mutations = await archive.mutations
        let requests = await socket.requests
        XCTAssertEqual(mutations, [.init(storedID: "stored-1", title: nil, archived: true, pinned: nil)])
        XCTAssertTrue(try XCTUnwrap(model.sessions.first).archived)
        XCTAssertNil(model.state.runtimeSessionID)
        XCTAssertNil(model.state.storedSessionID)
        XCTAssertTrue(requests.isEmpty)
    }

    func testRemotePinUsesDurableSessionMutationClientAndUpdatesSummary() async throws {
        let socket = RecordingSocket()
        let mutations = RecordingSessionMutationClient()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(
            transport: transport,
            sessionMutationClient: mutations,
            sessions: [
                SessionSummary(storedID: "stored-recent", title: "Recent", lastActive: 20),
                SessionSummary(storedID: "stored-1", title: "Roadmap", lastActive: 1)
            ]
        )

        try await model.connect()
        try await model.setPinned(true, storedSessionID: "stored-1")

        let recorded = await mutations.mutations
        let requests = await socket.requests
        XCTAssertEqual(recorded, [.init(storedID: "stored-1", pinned: true)])
        XCTAssertEqual(model.sessions.map(\.storedID), ["stored-1", "stored-recent"])
        XCTAssertTrue(try XCTUnwrap(model.sessions.first).pinned)
        XCTAssertTrue(requests.isEmpty)
    }

    func testRemoteDeleteUsesDurableSessionMutationClient() async throws {
        let socket = RecordingSocket()
        let archive = RecordingSessionMutationClient()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport, sessionMutationClient: archive)

        try await model.connect()
        try await model.delete(storedSessionID: "stored-2")

        let deletions = await archive.deletedIDs
        let requests = await socket.requests
        XCTAssertEqual(deletions, ["stored-2"])
        XCTAssertTrue(requests.isEmpty)
    }

    func testPromptResponsesUseExactGatewayParameterShapes() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")

        try await model.respondToApproval(.once)
        try await model.respondToClarify(requestID: "clarify-1", answer: "A")
        try await model.respondToSecret(requestID: "secret-1", value: "hidden")
        try await model.respondToSudo(requestID: "sudo-1", password: "hidden")

        let requests = await socket.requests
        XCTAssertEqual(requests[0].params, ["session_id": .string("runtime-1"), "choice": .string("once")])
        XCTAssertEqual(requests[1].params, ["request_id": .string("clarify-1"), "answer": .string("A")])
        XCTAssertEqual(requests[2].params, ["request_id": .string("secret-1"), "value": .string("hidden")])
        XCTAssertEqual(requests[3].params, ["request_id": .string("sudo-1"), "password": .string("hidden")])
    }

    func testModelAndReasoningControlsStayScopedToRuntimeSession() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        let selection = ModelOption(
            providerID: "nous",
            providerName: "Nous",
            modelID: "hermes-4",
            supportsFast: false,
            supportsReasoning: true
        )

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        _ = try await model.selectModel(selection)
        try await model.setReasoningEffort("max")

        let requests = await socket.requests
        XCTAssertEqual(requests.map(\.method), [GatewayMethod.configSet, GatewayMethod.configSet])
        XCTAssertEqual(requests[0].params, [
            "session_id": .string("runtime-1"),
            "key": .string("model"),
            "value": .string("hermes-4 --provider nous --session")
        ])
        XCTAssertEqual(requests[1].params, [
            "session_id": .string("runtime-1"),
            "key": .string("reasoning"),
            "value": .string("max")
        ])
        XCTAssertEqual(model.selectedModelID, "hermes-4")
        XCTAssertEqual(model.selectedProviderID, "nous")
        XCTAssertEqual(model.reasoningEffort, "max")
    }

    func testModelSelectionWaitsForExplicitExpensiveModelConfirmation() async throws {
        let socket = RecordingSocket(requireModelConfirmation: true)
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        let selection = ModelOption(
            providerID: "nous",
            providerName: "Nous",
            modelID: "expensive-model",
            supportsReasoning: true
        )

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        let pending = try await model.selectModel(selection)

        XCTAssertEqual(pending, .confirmationRequired(message: "Confirm expensive model"))
        XCTAssertEqual(model.selectedModelID, "")
        XCTAssertEqual(model.selectedProviderID, "")

        let applied = try await model.selectModel(selection, confirmExpensiveModel: true)
        XCTAssertEqual(applied, .applied)
        XCTAssertEqual(model.selectedModelID, "expensive-model")
        XCTAssertEqual(model.selectedProviderID, "nous")
        let requests = await socket.requests
        XCTAssertEqual(requests[0].params?["confirm_expensive_model"], nil)
        XCTAssertEqual(requests[1].params?["confirm_expensive_model"], .bool(true))
    }

    func testCapturedModelConfirmationCanCompleteAfterDialogDismissal() async throws {
        let socket = RecordingSocket(requireModelConfirmation: true)
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        let selection = ModelOption(
            providerID: "nous",
            providerName: "Nous",
            modelID: "expensive-model",
            supportsReasoning: true
        )

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        _ = try await model.selectModel(selection)
        let pending = try XCTUnwrap(model.pendingModelConfirmation)

        model.cancelPendingModelSelection()
        try await model.confirmModelSelection(pending)

        XCTAssertEqual(model.selectedModelID, "expensive-model")
        XCTAssertEqual(model.selectedProviderID, "nous")
        let requests = await socket.requests
        XCTAssertEqual(requests.count, 2)
        XCTAssertEqual(requests[1].params?["confirm_expensive_model"], .bool(true))
    }

    func testFailedModelSelectionDoesNotChangeAuthoritativeState() async throws {
        let socket = RecordingSocket(failConfigSet: true)
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        let selection = ModelOption(providerID: "nous", providerName: "Nous", modelID: "rejected-model")

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        do {
            _ = try await model.selectModel(selection)
            XCTFail("Expected config.set to fail")
        } catch {
            XCTAssertEqual(model.selectedModelID, "")
            XCTAssertEqual(model.selectedProviderID, "")
        }
    }

    func testForcedModelRefreshPropagatesRefreshFlag() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.loadModelOptions(refresh: true)

        let requests = await socket.requests
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.params?["refresh"], .bool(true))
    }

    func testLoadsModelCatalogForActiveRuntime() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.loadModelOptions()

        XCTAssertEqual(model.modelCatalog.currentModel, "fixture-model")
        XCTAssertEqual(model.modelCatalog.currentProvider, "fixture")
        XCTAssertEqual(model.modelCatalog.providers.map(\.id), ["fixture"])
        XCTAssertEqual(model.modelCatalog.providers.first?.models.map(\.modelID), ["fixture-model", "fixture-fast"])
        XCTAssertEqual(model.modelCatalog.providers.first?.models.last?.supportsFast, true)
        let requests = await socket.requests
        XCTAssertEqual(requests.map(\.method), [GatewayMethod.modelOptions])
        XCTAssertEqual(requests.first?.params?["session_id"], .string("runtime-1"))
    }
    func testResumeClearsOldCatalogAndAdoptsActiveSessionModelState() async throws {
        let socket = RecordingSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        try await model.loadModelOptions()
        XCTAssertFalse(model.modelCatalog.providers.isEmpty)

        _ = try await model.resume(storedSessionID: "stored-2")

        XCTAssertTrue(model.modelCatalog.providers.isEmpty)
        XCTAssertEqual(model.modelCatalog.currentModel, "active-model")
        XCTAssertEqual(model.modelCatalog.currentProvider, "active-provider")
        XCTAssertEqual(model.selectedModelID, "active-model")
        XCTAssertEqual(model.selectedProviderID, "active-provider")
        XCTAssertEqual(model.reasoningEffort, "high")
    }

    func testSessionInfoSynchronizesModelProviderAndReasoningForActiveRuntime() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        await socket.pushEvent(
            GatewayEvent(
                type: .sessionInfo,
                sessionID: "runtime-1",
                payload: .object([
                    "model": .string("event-model"),
                    "provider": .string("event-provider"),
                    "reasoning_effort": .string("ultra")
                ])
            )
        )
        for _ in 0..<50 {
            if model.selectedModelID == "event-model" { break }
            await Task.yield()
        }

        XCTAssertEqual(model.selectedModelID, "event-model")
        XCTAssertEqual(model.selectedProviderID, "event-provider")
        XCTAssertEqual(model.reasoningEffort, "ultra")
        XCTAssertEqual(model.modelCatalog.currentModel, "event-model")
        XCTAssertEqual(model.modelCatalog.currentProvider, "event-provider")
    }

    func testSlowerEarlierResumeCannotOverwriteNewerSessionSelection() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)

        try await model.connect()
        let first = Task { try await model.resume(storedSessionID: "stored-1") }
        let second = Task { try await model.resume(storedSessionID: "stored-2") }
        let requests = await socket.waitForRequests(count: 2)
        let firstRequest = try XCTUnwrap(requests.first { $0.params?["session_id"] == .string("stored-1") })
        let secondRequest = try XCTUnwrap(requests.first { $0.params?["session_id"] == .string("stored-2") })

        await socket.pushResponse(
            id: secondRequest.id,
            result: activeSessionResult(runtimeID: "runtime-2", storedID: "stored-2")
        )
        _ = try await second.value
        await socket.pushResponse(
            id: firstRequest.id,
            result: activeSessionResult(runtimeID: "runtime-1", storedID: "stored-1")
        )
        _ = try await first.value

        XCTAssertEqual(model.state.storedSessionID, "stored-2")
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-2")
    }

    func testNewModelOperationEndsSupersededCatalogLoadingState() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        let selection = ModelOption(providerID: "fixture", providerName: "Fixture", modelID: "new-model")

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        let refresh = Task { try await model.loadModelOptions() }
        _ = await socket.waitForRequests(count: 1)
        let selectionTask = Task { try await model.selectModel(selection) }
        let requests = await socket.waitForRequests(count: 2)
        let selectionRequest = try XCTUnwrap(requests.first { $0.method == GatewayMethod.configSet })

        await socket.pushResponse(id: selectionRequest.id, result: .object(["confirm_required": .bool(false)]))
        _ = try await selectionTask.value

        XCTAssertFalse(model.isLoadingModelOptions)

        let refreshRequest = try XCTUnwrap(requests.first { $0.method == GatewayMethod.modelOptions })
        await socket.pushResponse(id: refreshRequest.id, result: modelOptionsResult())
        try await refresh.value
        XCTAssertEqual(model.selectedModelID, "new-model")
    }

    func testSessionInfoModelChangeClearsPendingConfirmation() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("token"), socketFactory: { _ in socket })
        let model = ChatModel(transport: transport)
        let selection = ModelOption(providerID: "fixture", providerName: "Fixture", modelID: "expensive-model")

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        let selectionTask = Task { try await model.selectModel(selection) }
        let requests = await socket.waitForRequests(count: 1)
        let request = try XCTUnwrap(requests.first)
        await socket.pushResponse(
            id: request.id,
            result: .object([
                "confirm_required": .bool(true),
                "confirm_message": .string("Confirm expensive model")
            ])
        )
        _ = try await selectionTask.value
        XCTAssertNotNil(model.pendingModelConfirmation)

        await socket.pushEvent(
            GatewayEvent(
                type: .sessionInfo,
                sessionID: "runtime-1",
                payload: .object([
                    "model": .string("external-model"),
                    "provider": .string("fixture")
                ])
            )
        )
        for _ in 0..<50 {
            if model.selectedModelID == "external-model" { break }
            await Task.yield()
        }

        XCTAssertNil(model.pendingModelConfirmation)
    }

    func testNotificationSignalsMapRuntimeEventsToCanonicalStoredIdentity() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("token"),
            socketFactory: { _ in socket }
        )
        let model = ChatModel(transport: transport)
        var signals: [ChatSignal] = []

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-canonical")
        model.signalHandler = { signals.append($0) }

        await socket.pushEvent(GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-1",
            payload: .object([
                "status": .string("complete"),
                "text": .string("private response body")
            ])
        ))
        await socket.pushEvent(GatewayEvent(
            type: .approvalRequest,
            sessionID: "runtime-1",
            payload: .object([
                "request_id": .string("approval-1"),
                "command": .string("private command")
            ])
        ))
        await socket.pushEvent(GatewayEvent(
            type: .clarifyRequest,
            sessionID: "runtime-1",
            payload: .object([
                "request_id": .string("clarify-1"),
                "question": .string("private prompt")
            ])
        ))
        await socket.pushEvent(GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-stale",
            payload: .object(["status": .string("complete")])
        ))

        for _ in 0..<100 {
            if signals.count == 3 { break }
            await Task.yield()
        }

        XCTAssertEqual(signals, [
            .messageCompleted(storedSessionID: "stored-canonical"),
            .approvalRequired(storedSessionID: "stored-canonical"),
            .inputRequired(storedSessionID: "stored-canonical")
        ])
    }

    func testReplayDeduplicationPrecedesReducerAndSignalHandling() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("token"),
            socketFactory: { _ in socket }
        )
        let replayGuard = EventReplayGuard(capacity: 32)
        replayGuard.activate(profileScope: "profile-a")
        let model = ChatModel(
            transport: transport,
            eventReplayGuard: replayGuard,
            profileScope: "profile-a"
        )
        var signals: [ChatSignal] = []
        let signalsReceived = expectation(description: "deduplicated completion and approval signals")
        signalsReceived.expectedFulfillmentCount = 2
        signalsReceived.assertForOverFulfill = true

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        model.signalHandler = {
            signals.append($0)
            signalsReceived.fulfill()
        }

        let completion = GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-1",
            payload: .object([
                "event_id": .string("completion-event"),
                "message_id": .string("message-1"),
                "status": .string("complete"),
                "text": .string("answer")
            ])
        )
        let approval = GatewayEvent(
            type: .approvalRequest,
            sessionID: "runtime-1",
            payload: .object([
                "event_id": .string("approval-event"),
                "request_id": .string("approval-1"),
                "command": .string("private command")
            ])
        )
        await socket.pushEvent(completion)
        await socket.pushEvent(completion)
        await socket.pushEvent(approval)
        await socket.pushEvent(approval)

        await fulfillment(of: [signalsReceived], timeout: 2)

        XCTAssertEqual(model.state.messages.map(\.text), ["answer"])
        XCTAssertEqual(model.state.approval?.command, "private command")
        XCTAssertEqual(signals, [
            .messageCompleted(storedSessionID: "stored-1"),
            .approvalRequired(storedSessionID: "stored-1")
        ])
    }

    func testStreamingFramesOnlyDeduplicateWithIndependentEventIdentity() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("token"),
            socketFactory: { _ in socket }
        )
        let model = ChatModel(transport: transport)

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-1", storedID: "stored-1")
        await socket.pushEvent(GatewayEvent(
            type: .messageStart,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1")])
        ))
        await socket.pushEvent(GatewayEvent(
            type: .messageDelta,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1"), "text": .string("a")])
        ))
        await socket.pushEvent(GatewayEvent(
            type: .messageDelta,
            sessionID: "runtime-1",
            payload: .object(["message_id": .string("message-1"), "text": .string("b")])
        ))
        let replayedDelta = GatewayEvent(
            type: .messageDelta,
            sessionID: "runtime-1",
            payload: .object([
                "event_id": .string("delta-event"),
                "message_id": .string("message-1"),
                "text": .string("c")
            ])
        )
        await socket.pushEvent(replayedDelta)
        await socket.pushEvent(replayedDelta)

        for _ in 0..<100 {
            if model.state.messages.last?.text == "abc" { break }
            await Task.yield()
        }

        XCTAssertEqual(model.state.messages.last?.text, "abc")
    }

    func testStaleRuntimeDoesNotPolluteReplayGuard() async throws {
        let socket = ControlledChatSocket()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("token"),
            socketFactory: { _ in socket }
        )
        let model = ChatModel(transport: transport)
        var signals: [ChatSignal] = []

        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-current", storedID: "stored-1")
        model.signalHandler = { signals.append($0) }
        let event = GatewayEvent(
            type: .messageComplete,
            sessionID: "runtime-stale",
            payload: .object([
                "event_id": .string("completion-event"),
                "message_id": .string("message-1"),
                "status": .string("complete"),
                "text": .string("answer")
            ])
        )
        await socket.pushEvent(event)
        model.setRuntimeSession(runtimeID: "runtime-stale", storedID: "stored-1")
        await socket.pushEvent(event)

        for _ in 0..<100 {
            if signals.count == 1 { break }
            await Task.yield()
        }

        XCTAssertEqual(model.state.messages.map(\.text), ["answer"])
        XCTAssertEqual(signals, [.messageCompleted(storedSessionID: "stored-1")])
    }

    func testRestoreArchivedSessionRefreshesWithoutChangingIdentity() async throws {
        let socket = ControlledChatSocket()
        let mutations = RecordingSessionMutationClient()
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("token"),
            socketFactory: { _ in socket }
        )
        let archived = SessionSummary(storedID: "archived", archived: true)
        let model = ChatModel(
            transport: transport,
            sessionMutationClient: mutations,
            sessions: [archived]
        )
        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-current", storedID: "current")
        let restoration = Task {
            try await model.restoreArchivedSession(storedSessionID: "archived")
        }
        let requests = await socket.waitForRequests(count: 1)
        let request = try XCTUnwrap(requests.first)
        XCTAssertEqual(request.method, GatewayMethod.sessionList)
        await socket.pushResponse(id: request.id, result: sessionLibraryResult())
        try await restoration.value
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-current")
        XCTAssertEqual(model.state.storedSessionID, "current")
        XCTAssertEqual(model.sessions.map(\.storedID), ["archived"])
        XCTAssertFalse(try XCTUnwrap(model.sessions.first).archived)
        let recorded = await mutations.mutations
        XCTAssertEqual(recorded, [.init(storedID: "archived", archived: false)])
    }

    func testRestoreFailureKeepsArchivedSummaryAndIdentity() async throws {
        let socket = ControlledChatSocket()
        let mutations = RecordingSessionMutationClient(failPatches: true)
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1:8765")
        let transport = HermesGatewayTransport(
            endpoint: endpoint,
            auth: .token("token"),
            socketFactory: { _ in socket }
        )
        let archived = SessionSummary(storedID: "archived", archived: true)
        let model = ChatModel(
            transport: transport,
            sessionMutationClient: mutations,
            sessions: [archived]
        )
        try await model.connect()
        model.setRuntimeSession(runtimeID: "runtime-current", storedID: "current")
        do {
            try await model.restoreArchivedSession(storedSessionID: "archived")
            XCTFail("Expected restore failure")
        } catch {}
        XCTAssertEqual(model.state.runtimeSessionID, "runtime-current")
        XCTAssertEqual(model.state.storedSessionID, "current")
        XCTAssertTrue(try XCTUnwrap(model.sessions.first).archived)
    }

    func testDemoSeedProvidesModelControlsCatalog() throws {
        let endpoint = try GatewayEndpoint(rawValue: "http://127.0.0.1")
        let transport = HermesGatewayTransport(endpoint: endpoint, auth: .token("demo"), socketFactory: { _ in
            fatalError("demo transport must not open a socket")
        })
        let model = ChatModel(transport: transport)

        model.seedDemo()

        let option = try XCTUnwrap(model.modelCatalog.providers.first?.models.first)
        XCTAssertEqual(model.selectedModelID, option.modelID)
        XCTAssertEqual(model.selectedProviderID, option.providerID)
        XCTAssertTrue(option.supportsReasoning)
    }
}

private actor RecordingSocket: GatewaySocket {
    var requests: [JSONRPCRequest] = []
    private var responses: [Data] = []
    private var waiters: [CheckedContinuation<Data, Error>] = []
    private let requireModelConfirmation: Bool
    private let failConfigSet: Bool

    init(requireModelConfirmation: Bool = false, failConfigSet: Bool = false) {
        self.requireModelConfirmation = requireModelConfirmation
        self.failConfigSet = failConfigSet
    }

    func send(_ data: Data) async throws {
        let request = try JSONDecoder().decode(JSONRPCRequest.self, from: data)
        requests.append(request)
        if request.method == GatewayMethod.configSet && failConfigSet {
            let response = try JSONEncoder().encode(
                JSONRPCResponse(
                    id: request.id,
                    error: JSONRPCError(code: 5001, message: "Rejected model switch")
                )
            )
            enqueue(response)
            return
        }
        let result: JSONValue
        if request.method == GatewayMethod.modelOptions {
            result = .object([
                "model": .string("fixture-model"),
                "provider": .string("fixture"),
                "providers": .array([
                    .object([
                        "slug": .string("fixture"),
                        "name": .string("Fixture"),
                        "authenticated": .bool(true),
                        "models": .array([.string("fixture-model"), .string("fixture-fast")]),
                        "capabilities": .object([
                            "fixture-model": .object(["fast": .bool(false), "reasoning": .bool(true)]),
                            "fixture-fast": .object(["fast": .bool(true), "reasoning": .bool(true)])
                        ])
                    ])
                ])
            ])
        } else if request.method == GatewayMethod.sessionResume {
            result = .object([
                "session_id": .string("runtime-resumed"),
                "resumed": .string("stored-2"),
                "info": .object([
                    "model": .string("active-model"),
                    "provider": .string("active-provider"),
                    "reasoning_effort": .string("high")
                ]),
                "messages": .array([])
            ])
        } else if request.method == GatewayMethod.configSet,
                  request.params?["key"] == .string("model"),
                  requireModelConfirmation,
                  request.params?["confirm_expensive_model"] != .bool(true) {
            result = .object([
                "confirm_required": .bool(true),
                "confirm_message": .string("Confirm expensive model")
            ])
        } else {
            result = .object(["status": .string("ok")])
        }
        let response = try JSONEncoder().encode(
            JSONRPCResponse(id: request.id, result: result)
        )
        enqueue(response)
    }

    func receive() async throws -> Data {
        if let response = responses.first {
            responses.removeFirst()
            return response
        }
        return try await withCheckedThrowingContinuation { continuation in
            waiters.append(continuation)
        }
    }

    func cancel() async {
        for waiter in waiters {
            waiter.resume(throwing: GatewayTransportError.connectionLost)
        }
        waiters.removeAll()
    }

    private func enqueue(_ response: Data) {
        if let waiter = waiters.first {
            waiters.removeFirst()
            waiter.resume(returning: response)
        } else {
            responses.append(response)
        }
    }
}

private actor ControlledChatSocket: GatewaySocket {
    private var requests: [JSONRPCRequest] = []
    private var requestWaiters: [(Int, CheckedContinuation<[JSONRPCRequest], Never>)] = []
    private var incoming: [Data] = []
    private var incomingWaiters: [CheckedContinuation<Data, Error>] = []

    func send(_ data: Data) async throws {
        requests.append(try JSONDecoder().decode(JSONRPCRequest.self, from: data))
        let ready = requestWaiters.filter { requests.count >= $0.0 }
        requestWaiters.removeAll { requests.count >= $0.0 }
        for (_, waiter) in ready { waiter.resume(returning: requests) }
    }

    func receive() async throws -> Data {
        if let data = incoming.first {
            incoming.removeFirst()
            return data
        }
        return try await withCheckedThrowingContinuation { continuation in
            incomingWaiters.append(continuation)
        }
    }

    func cancel() async {
        for waiter in incomingWaiters {
            waiter.resume(throwing: GatewayTransportError.connectionLost)
        }
        incomingWaiters.removeAll()
    }

    func waitForRequests(count: Int) async -> [JSONRPCRequest] {
        if requests.count >= count { return Array(requests.prefix(count)) }
        return await withCheckedContinuation { continuation in
            requestWaiters.append((count, continuation))
        }
    }

    func pushResponse(id: JSONRPCID, result: JSONValue) {
        let data = try! JSONEncoder().encode(JSONRPCResponse(id: id, result: result))
        enqueue(data)
    }

    func pushEvent(_ event: GatewayEvent) {
        let data = try! JSONEncoder().encode(JSONRPCEventFrame(event: event))
        enqueue(data)
    }

    private func enqueue(_ data: Data) {
        if let waiter = incomingWaiters.first {
            incomingWaiters.removeFirst()
            waiter.resume(returning: data)
        } else {
            incoming.append(data)
        }
    }
}

private func activeSessionResult(runtimeID: String, storedID: String) -> JSONValue {
    .object([
        "session_id": .string(runtimeID),
        "resumed": .string(storedID),
        "messages": .array([]),
        "info": .object([
            "model": .string("model-\(storedID)"),
            "provider": .string("fixture"),
            "reasoning_effort": .string("high")
        ])
    ])
}

private func sessionLibraryResult() -> JSONValue {
    .object(["sessions": .array([
        .object([
            "id": .string("archived"),
            "title": .string("Restored"),
            "archived": .bool(false),
            "pinned": .bool(false)
        ])
    ])])
}

private func modelOptionsResult() -> JSONValue {
    .object([
        "model": .string("fixture-model"),
        "provider": .string("fixture"),
        "providers": .array([])
    ])
}

private actor RecordingSessionMutationClient: SessionMutationClient {
    var mutations: [SessionMutation] = []
    var deletedIDs: [String] = []
    private let failPatches: Bool

    init(failPatches: Bool = false) {
        self.failPatches = failPatches
    }

    func patchSession(_ mutation: SessionMutation) async throws {
        if failPatches { throw GatewayTransportError.connectionLost }
        mutations.append(mutation)
    }

    func deleteSession(_ storedID: String) async throws {
        deletedIDs.append(storedID)
    }
}
