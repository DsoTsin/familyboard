import com.google.protobuf.gradle.*

plugins {
    id("com.android.application") version "8.11.1"
    id("org.jetbrains.kotlin.android") version "2.1.21"
    id("org.jetbrains.kotlin.plugin.compose") version "2.1.21"
    id("com.google.protobuf") version "0.9.5"
}

android {
    namespace = "com.familyboard"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.familyboard.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }

    buildFeatures { compose = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets.getByName("main").proto.srcDir("../../proto")
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

protobuf {
    protoc { artifact = "com.google.protobuf:protoc:3.25.8" }
    plugins { id("grpc") { artifact = "io.grpc:protoc-gen-grpc-java:1.73.0" } }
    generateProtoTasks {
        all().configureEach {
            builtins { named("java") { option("lite") } }
            plugins { id("grpc") { option("lite") } }
        }
    }
}

dependencies {
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.0")
    implementation("androidx.compose.foundation:foundation:1.8.3")
    implementation("androidx.compose.material3:material3:1.3.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("io.grpc:grpc-okhttp:1.73.0")
    implementation("io.grpc:grpc-protobuf-lite:1.73.0")
    implementation("io.grpc:grpc-stub:1.73.0")
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}
