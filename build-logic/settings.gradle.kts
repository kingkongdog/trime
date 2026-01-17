// SPDX-FileCopyrightText: 2015 - 2024 Rime community
//
// SPDX-License-Identifier: GPL-3.0-or-later

dependencyResolutionManagement {
    repositories {
        maven { url=uri ("https://jitpack.io") }
        maven { url=uri ("https://maven.aliyun.com/repository/releases") }
        maven { url=uri ("https://maven.aliyun.com/repository/google") }
        maven { url=uri ("https://maven.aliyun.com/repository/central") }
        maven { url=uri ("https://maven.aliyun.com/repository/gradle-plugin") }
        maven { url=uri ("https://maven.aliyun.com/repository/public") }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    versionCatalogs {
        create("libs") {
            from(files("../gradle/libs.versions.toml"))
        }
    }
}

rootProject.name = "build-logic"
include(":convention")
