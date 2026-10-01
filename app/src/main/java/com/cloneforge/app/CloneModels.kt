package com.cloneforge.app

data class CloneRequest(val sourcePackage: String, val cloneName: String, val clonePackage: String)
data class ClonePreparation(val sourcePackage: String, val clonePackage: String, val workspacePath: String, val sourceApkPath: String)
