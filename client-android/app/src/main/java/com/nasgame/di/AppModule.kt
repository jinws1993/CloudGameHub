package com.nasgame.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Hilt 模块。
 *
 * **已经空了** —— 这是重构留下的印记, 之前这里塞的是 Retrofit + OkHttp + 服务器
 * 地址。现在 App 完全独立, 所有依赖都靠 @Inject constructor 构造, 不需要模块。
 *
 * 保留这个文件而不是删掉, 是因为以后加全局单例 (比如统一崩溃上报) 时有地方放。
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun appVersionNote(): String = "1.3.0-standalone"
}
