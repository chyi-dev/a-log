plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.chyi.alog.decode"
    compileSdk = 34

    defaultConfig {
        minSdk = 21
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    implementation(project(":alog"))
    testImplementation(libs.junit)
}

afterEvaluate {
    tasks.register<JavaExec>("run") {
        group = "application"
        description = "Decode .alog files to JSON lines"
        dependsOn("compileDebugKotlin")
        mainClass.set("com.chyi.alog.decode.DecodeCommand")
        classpath = files(
            layout.buildDirectory.dir("tmp/kotlin-classes/debug"),
            configurations.getByName("debugCompileClasspath"),
        )
    }
}
