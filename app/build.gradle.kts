plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.classgrid.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.classgrid.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 93
        versionName = "1.48.11"
        // v1.42.0 起全面转为内测版：界面与日志里直接写明，避免用户把内测包当正式版反馈
        buildConfigField("String", "CHANNEL", "\"内测版\"")

        // bundled ML Kit 模型体积较大，仅保留主流 ARM 架构以控制 APK 体积。
        // 模拟器验证时用 -PclassgridAbis=arm64-v8a,armeabi-v7a,x86_64 临时加架构（ML Kit 自带 x86_64 .so）。
        val abiList = (findProperty("classgridAbis") as String?)
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: listOf("arm64-v8a", "armeabi-v7a")
        ndk {
            abiFilters += abiList
        }
    }

    // 签名口令不进仓库：从 local.properties（已被 .gitignore 忽略）或环境变量读取。
    // 本地要打 release 包时，在 local.properties 里写：
    //   classgrid.keystore=../release.keystore
    //   classgrid.storePassword=你的口令
    //   classgrid.keyAlias=classgrid
    //   classgrid.keyPassword=你的口令
    // 没有配置时 release 包退回 debug 签名（能装能跑，只是不能覆盖已发布的正式包）。
    val localPropsText = rootProject.file("local.properties")
        .takeIf { it.exists() }?.readText().orEmpty()
    fun localProp(key: String): String? = localPropsText.lineSequence()
        .map { it.trim() }
        .firstOrNull { it.startsWith("$key=") }
        ?.substringAfter('=')?.trim()?.takeIf { it.isNotEmpty() }
    val keystorePath = localProp("classgrid.keystore")
        ?: System.getenv("CLASSGRID_KEYSTORE")
    val storePass = localProp("classgrid.storePassword")
        ?: System.getenv("CLASSGRID_STORE_PASSWORD")
    val keyAliasName = localProp("classgrid.keyAlias")
        ?: System.getenv("CLASSGRID_KEY_ALIAS") ?: "classgrid"
    val keyPass = localProp("classgrid.keyPassword")
        ?: System.getenv("CLASSGRID_KEY_PASSWORD")
    val releaseKeystore = keystorePath
        ?.let { file(it) }
        ?.takeIf { it.exists() && !storePass.isNullOrEmpty() }

    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = storePass
                keyAlias = keyAliasName
                keyPassword = keyPass ?: storePass
                // 同时产出 v1(JAR) 与 v2 签名。
                // 只带 v2 时，个别渠道/旧安装器会报「文件损坏 / 解析包时出现问题」，
                // v1 签名给它多一层兜底兼容（v1.30~v1.34.0 只有 v2）。
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = false
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
                ?: signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        viewBinding = true
        // AGP 8.x 默认关闭 BuildConfig 生成；界面版本号需读取 BuildConfig.VERSION_NAME，必须显式开启
        buildConfig = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    // 无真机/模拟器时的唯一自动化验证手段：把"纯逻辑类"（解析器/数据层）放到 JVM 单测里跑。
    // 注意这不会让 android.* API 变得可用（返回默认值/抛异常），所以单测只覆盖不依赖 Context 的代码。
    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

// 项目路径含中文（android.overridePathCheck=true 放行）。真正的坑在守护进程：若给 daemon 加
// -Dfile.encoding=UTF-8，Gradle 会用 UTF-8 写 worker 的 @argfile，而 JDK17 的启动器按系统 ANSI
// 编码（中文 Windows = GBK）读回 → 中文路径乱码 → 所有单测报 ClassNotFoundException。
// 因此 daemon 编码保持系统默认（见 gradle.properties），这里只把 worker 自身钉成 UTF-8。
tasks.withType<Test>().configureEach {
    systemProperty("file.encoding", "UTF-8")
    jvmArgs("-Dfile.encoding=UTF-8")
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    // bundled 版中文文字识别：模型经 text-recognition-bundled-common 静态打包进 APK，
    // 完全离线可用（不依赖设备 GMS 动态下发）。
    // 注意：ML Kit 文字识别 v2 的命名与直觉相反 —— com.google.mlkit:* 才是 bundled，
    // com.google.android.gms:play-services-mlkit-* 是需要 Play Services 下发模型的 unbundled。
    implementation("com.google.mlkit:text-recognition-chinese:16.0.1")

    // ---- JVM 单元测试（不打进 APK）----
    // 本项目没有真机/模拟器，解析器（WebScheduleParser / OcrParser / GridParser）与数据层是纯 Kotlin，
    // 用 JVM 单测验证是"能不能跑对"的主要证据来源。APK 体积不受影响。
    testImplementation("junit:junit:4.13.2")
    // Android 运行时的 org.json 是系统内置实现；JVM 单测里必须引入真实实现，否则 JSONObject 是空壳。
    testImplementation("org.json:json:20240303")
}
