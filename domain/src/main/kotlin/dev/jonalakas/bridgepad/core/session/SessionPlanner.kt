package dev.jonalakas.bridgepad.core.session

data class SessionDraft(
    val destinationType: DestinationType? = null,
    val connectionMethod: ConnectionMethod? = null,
    val outputAdapterId: OutputAdapterId? = null,
    val destinationTarget: DestinationTarget? = null,
    val inputMode: InputMode? = null,
    val physicalCaptureMode: PhysicalCaptureMode? = null,
) {
    fun selectDestination(destination: DestinationType) = copy(
        destinationType = destination,
        connectionMethod = null,
        outputAdapterId = null,
        destinationTarget = null,
        inputMode = null,
        physicalCaptureMode = null,
    )

    fun selectConnection(method: ConnectionMethod, adapterId: OutputAdapterId? = null) = copy(
        connectionMethod = method,
        outputAdapterId = adapterId,
        destinationTarget = null,
        inputMode = null,
        physicalCaptureMode = null,
    )

    fun selectTarget(target: DestinationTarget) = copy(
        destinationTarget = target,
        inputMode = null,
        physicalCaptureMode = null,
    )

    fun selectInput(mode: InputMode) = copy(
        inputMode = mode,
        physicalCaptureMode = if (mode == InputMode.PHYSICAL_GAMEPAD) physicalCaptureMode else null,
    )
}

enum class SessionPlanProblem {
    MISSING_DESTINATION,
    MISSING_CONNECTION,
    ADAPTER_UNAVAILABLE,
    AMBIGUOUS_ADAPTER,
    INCOMPATIBLE_ADAPTER,
    CONNECTION_UNAVAILABLE,
    MISSING_TARGET,
    TARGET_UNAVAILABLE,
    MISSING_INPUT,
    MISSING_CAPTURE_MODE,
}

sealed interface SessionPlanResult {
    data class Ready(val configuration: SessionConfiguration) : SessionPlanResult
    data class Incomplete(val problem: SessionPlanProblem) : SessionPlanResult
}

class OutputAdapterCatalog(descriptors: Collection<OutputAdapterDescriptor>) {
    private val byId = descriptors.associateBy(OutputAdapterDescriptor::id)

    init {
        require(byId.size == descriptors.size) { "Output adapter ids must be unique." }
    }

    fun descriptor(id: OutputAdapterId): OutputAdapterDescriptor? = byId[id]

    fun compatibleWith(
        destination: DestinationType,
        connection: ConnectionMethod,
    ): List<OutputAdapterDescriptor> = byId.values.filter { it.supports(destination, connection) }

    fun all(): List<OutputAdapterDescriptor> = byId.values.toList()
}

object SessionPlanner {
    fun plan(
        draft: SessionDraft,
        adapters: OutputAdapterCatalog,
        connectionAvailable: Boolean,
        availableTargetIds: Collection<String>,
    ): SessionPlanResult {
        val destination = draft.destinationType
            ?: return SessionPlanResult.Incomplete(SessionPlanProblem.MISSING_DESTINATION)
        val connection = draft.connectionMethod
            ?: return SessionPlanResult.Incomplete(SessionPlanProblem.MISSING_CONNECTION)
        val compatibleAdapters = adapters.compatibleWith(destination, connection)
        val adapter = if (draft.outputAdapterId != null) {
            adapters.descriptor(draft.outputAdapterId)
                ?: return SessionPlanResult.Incomplete(SessionPlanProblem.ADAPTER_UNAVAILABLE)
        } else {
            when (compatibleAdapters.size) {
                0 -> return SessionPlanResult.Incomplete(SessionPlanProblem.ADAPTER_UNAVAILABLE)
                1 -> compatibleAdapters.single()
                else -> return SessionPlanResult.Incomplete(SessionPlanProblem.AMBIGUOUS_ADAPTER)
            }
        }
        if (!adapter.supports(destination, connection)) {
            return SessionPlanResult.Incomplete(SessionPlanProblem.INCOMPATIBLE_ADAPTER)
        }
        if (!connectionAvailable) {
            return SessionPlanResult.Incomplete(SessionPlanProblem.CONNECTION_UNAVAILABLE)
        }
        val target = draft.destinationTarget
        if (adapter.targetSelectionMode != TargetSelectionMode.NONE && target == null) {
            return SessionPlanResult.Incomplete(SessionPlanProblem.MISSING_TARGET)
        }
        if (target?.kind == DestinationTargetKind.EXISTING && target.id !in availableTargetIds) {
            return SessionPlanResult.Incomplete(SessionPlanProblem.TARGET_UNAVAILABLE)
        }
        val input = draft.inputMode
            ?: return SessionPlanResult.Incomplete(SessionPlanProblem.MISSING_INPUT)
        if (input == InputMode.PHYSICAL_GAMEPAD && draft.physicalCaptureMode == null) {
            return SessionPlanResult.Incomplete(SessionPlanProblem.MISSING_CAPTURE_MODE)
        }
        return SessionPlanResult.Ready(
            SessionConfiguration(
                destinationType = destination,
                connectionMethod = connection,
                outputAdapterId = adapter.id,
                destinationTarget = target,
                inputMode = input,
                physicalCaptureMode = draft.physicalCaptureMode,
            ),
        )
    }
}
