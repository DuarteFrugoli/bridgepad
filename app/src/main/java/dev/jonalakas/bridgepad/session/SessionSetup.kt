package dev.jonalakas.bridgepad.session

import dev.jonalakas.bridgepad.core.session.OutputAdapterCatalog
import dev.jonalakas.bridgepad.core.session.SessionDraft
import dev.jonalakas.bridgepad.core.session.SessionPlanResult
import dev.jonalakas.bridgepad.core.session.SessionPlanner

internal object SessionSetup {
    fun canConnect(
        draft: SessionDraft,
        adapters: OutputAdapterCatalog,
        connectionAvailable: Boolean,
        availableTargetIds: Collection<String>,
    ): Boolean = SessionPlanner.plan(
        draft = draft,
        adapters = adapters,
        connectionAvailable = connectionAvailable,
        availableTargetIds = availableTargetIds,
    ) is SessionPlanResult.Ready
}
