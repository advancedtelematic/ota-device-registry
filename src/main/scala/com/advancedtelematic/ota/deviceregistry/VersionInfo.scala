/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry

import com.advancedtelematic.BuildInfo

trait VersionInfo {
  lazy val projectName: String = BuildInfo.name

  lazy val version: String = {
    val bi = BuildInfo
    s"${bi.name}/${bi.version}"
  }

  lazy val versionMap: Map[String, Any] = BuildInfo.toMap
}
