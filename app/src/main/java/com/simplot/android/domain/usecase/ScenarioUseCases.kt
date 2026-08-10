package com.simplot.android.domain.usecase

import android.content.Context
import android.net.Uri
import com.simplot.android.data.repo.ScenarioRepository
import com.simplot.android.data.model.ScenarioFile
import com.simplot.android.engine.FogOfWar

/**
 * 场景加载/保存 UseCase（文档 §4.1 LoadScenarioUseCase / SaveScenarioUseCase）。
 *
 * 把 ViewModel 的场景 I/O 编排抽为可复用服务：
 * - load：读存档（自动识别 json/SpScn）
 * - saveThreeFiles：保存 Referee json + Blue/Red.SpScn（感知过滤）
 * - saveAuto：自动存档
 * - saveSetup：Setup 文件
 *
 * Android Context 由构造注入（Repository 需要）；纯逻辑部分可 JVM 测试。
 */
class ScenarioUseCases(context: Context) {
    private val repo = ScenarioRepository(context)

    /** 加载存档 */
    fun load(uri: Uri): Result<ScenarioFile> = try {
        Result.success(repo.load(uri))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 保存三文件（感知过滤红蓝视角） */
    fun saveThreeFiles(targetUri: Uri, current: ScenarioFile): Result<Boolean> = try {
        val blueView = FogOfWar.applyPerspective(current, "Blue")
        val redView = FogOfWar.applyPerspective(current, "Red")
        val parent = repo.parentTreeUri(targetUri)
        Result.success(repo.saveTo(targetUri, parent, current, blueView, redView))
    } catch (e: Exception) {
        Result.failure(e)
    }

    /** 自动存档（静默失败） */
    fun saveAuto(targetFileUri: Uri, data: ScenarioFile, turnNumber: Int): Boolean =
        repo.saveAuto(targetFileUri, data, turnNumber)

    /** Setup 文件保存 */
    fun saveSetup(target: Uri, data: ScenarioFile): Boolean =
        repo.saveSetup(target, data)
}
