package com.kieronquinn.app.utag.networking.services

import android.content.Context
import com.google.gson.Gson
import com.google.gson.JsonSyntaxException
import com.google.gson.reflect.TypeToken
import com.kieronquinn.app.utag.model.database.cache.CacheItem.CacheType
import com.kieronquinn.app.utag.networking.model.smartthings.GetInstalledAppsResponse
import com.kieronquinn.app.utag.networking.model.smartthings.InstalledAppsRequest
import com.kieronquinn.app.utag.networking.model.smartthings.InstalledAppsRequest.Companion.Method
import com.kieronquinn.app.utag.networking.model.smartthings.InstalledAppsResponse
import com.kieronquinn.app.utag.repositories.CacheRepository
import com.kieronquinn.app.utag.repositories.CacheRepository.Companion.getCache
import com.kieronquinn.app.utag.repositories.UserRepository
import com.kieronquinn.app.utag.utils.extensions.executeOrNull
import com.kieronquinn.app.utag.utils.extensions.get
import com.kieronquinn.app.utag.utils.extensions.reportRetrofitError
import com.kieronquinn.app.utag.utils.extensions.smartThingsClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.ResponseBody
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import retrofit2.Call
import retrofit2.Retrofit
import retrofit2.http.Body
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.Url
import java.io.IOException

interface InstalledAppsService {

    companion object: KoinComponent {

        private const val PLUGIN_ID_FME = "com.samsung.android.plugin.fme"

        private const val URL_EXECUTE_TEMPLATE =
            "https://api.smartthings.com/installedapps/%s/execute"

        private const val URL_GET_INSTALLED_APPS =
            "https://api.smartthings.com/installedapps?allowed=true"

        private val installedAppIdLock = Mutex()
        private var installedAppId: String? = null
        private val userRepository by inject<UserRepository>()
        private val cacheRepository by inject<CacheRepository>()

        suspend fun InstalledAppsService.getExecuteUrl(): String? {
            return getInstalledAppId()?.let {
                URL_EXECUTE_TEMPLATE.format(installedAppId)
            }
        }

        suspend fun InstalledAppsService.getInstalledAppId(): String? {
            return installedAppIdLock.withLock {
                getInstalledAppIdLocked()
            }
        }

        private suspend fun InstalledAppsService.getInstalledAppIdLocked(): String? {
            installedAppId?.let { return it }
            val userId = userRepository.getUserInfo()?.uuid ?: return null
            val allInstalledApps = ArrayList<GetInstalledAppsResponse.Item>()
            var url: String? = URL_GET_INSTALLED_APPS
            while(url != null) {
                val installedApps = getInstalledApps(url).get(name = "installedAppId") ?: break
                allInstalledApps.addAll(installedApps.items)
                url = installedApps.links.next?.href
            }
            val appId = allInstalledApps.getFmeAppId(userId)
                ?: cacheRepository.getCache<String>(CacheType.CURRENT_INSTALLED_APP_ID)
                ?: return null
            installedAppId = appId
            cacheRepository.setCache(CacheType.CURRENT_INSTALLED_APP_ID, data = appId)
            return appId
        }

        private fun List<GetInstalledAppsResponse.Item>.getFmeAppId(ownerId: String): String? {
            return firstOrNull {
                it.ui.pluginId == PLUGIN_ID_FME && it.owner.ownerId == ownerId
            }?.installedAppId ?: firstOrNull {
                it.ui.pluginId == PLUGIN_ID_FME
            }?.installedAppId
        }

        fun createService(context: Context, retrofit: Retrofit): InstalledAppsService {
            return retrofit.newBuilder()
                .client(smartThingsClient(context, true))
                .build()
                .create(InstalledAppsService::class.java)
        }

        suspend inline fun <reified T : Any> InstalledAppsService.get(
            method: Method,
            uri: String,
            extraUri: String? = null,
            extraParameters: Map<String, String> = emptyMap(),
            headers: Any? = null,
            body: Any? = null,
            type: CacheType? = null,
            subType: String? = null,
            crossinline convert: T.() -> T = { this }
        ): T? {
            val url = getExecuteUrl() ?: return null
            val cache by inject<CacheRepository>()
            val response = try {
                execute(
                    url,
                    InstalledAppsRequest.createRequest(
                        method, uri, extraUri, extraParameters, headers, body
                    )
                ).get(type != null, name = "$uri$extraUri")?.string() ?: return null
            }catch (e: IOException) {
                //Cache is enabled, so try to use the cache
                return cache.getCache<T>(type ?: return null, subType)?.convert()
            }
            val gson by inject<Gson>()
            val errorUrl = uri + (extraUri ?: "")
            val name = type?.name ?: errorUrl
            return try {
                val typeToken = object: TypeToken<InstalledAppsResponse<T>>(){}.type
                val installedAppsResponse = gson.fromJson<InstalledAppsResponse<T>>(response, typeToken)
                if(installedAppsResponse.statusCode == 200) {
                    installedAppsResponse.message.also {
                        //Set the cache for later if a type is specified
                        if(type != null) {
                            cache.setCache(type, subType, it)
                        }
                    }
                }else{
                    //Send just the basic data, we can't extract anything else here
                    reportRetrofitError(name, url = errorUrl, code = installedAppsResponse.statusCode)
                    null
                }
            }catch (e: JsonSyntaxException) {
                try {
                    //Try to parse the response as an error to get the reason
                    val typeToken = object: TypeToken<InstalledAppsResponse<String>>(){}.type
                    val errorResponse = gson
                        .fromJson<InstalledAppsResponse<String>>(response, typeToken)
                    val errorCode = errorResponse.statusCode
                    val error = "${errorResponse.errorCode}: ${errorResponse.message}"
                    reportRetrofitError(name, error = error, url = errorUrl, code = errorCode)
                }catch (e: Exception) {
                    //Otherwise just report the error
                    reportRetrofitError(name, exception = e)
                }
                null
            } catch (e: Exception) {
                //Error which was not caused by JSON, report it
                reportRetrofitError(name, exception = e)
                null
            }
        }

        suspend fun InstalledAppsService.request(
            method: Method,
            uri: String,
            extraUri: String? = null,
            extraParameters: Map<String, String> = emptyMap(),
            headers: Any? = null,
            body: Any? = null
        ): Unit? {
            return get<Unit>(method, uri, extraUri, extraParameters, headers, body)
        }

        suspend fun InstalledAppsService.send(
            method: Method,
            uri: String,
            extraUri: String? = null,
            extraParameters: Map<String, String> = emptyMap(),
            headers: Any? = null,
            body: Any? = null
        ): Boolean {
            val url = getExecuteUrl() ?: return false
            return execute(
                url,
                InstalledAppsRequest.createRequest(
                    method, uri, extraUri, extraParameters, headers, body
                )
            ).executeOrNull(name = "$uri$extraUri") != null
        }
    }

    @GET
    fun getInstalledApps(@Url url: String): Call<GetInstalledAppsResponse>

    @POST
    fun execute(@Url url: String, @Body request: InstalledAppsRequest): Call<ResponseBody>

}