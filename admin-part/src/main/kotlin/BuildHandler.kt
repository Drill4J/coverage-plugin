package com.epam.drill.plugins.test2code

import com.epam.drill.plugin.api.end.*

internal suspend fun Plugin.toggleBaseline(): ActionResult =
    state.toggleBaseline()?.let {
        adminData.buildManager.builds.forEach { buildInfo ->
            sendBaseline(buildInfo.version)
        }
        ActionResult(
            code = StatusCodes.OK,
            data = "Set baseline to '$it'"
        )
    } ?: ActionResult(
        code = StatusCodes.BAD_REQUEST,
        data = "Cannot uncheck baseline for initial build."
    )
