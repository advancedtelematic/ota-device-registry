/*
 * Copyright (c) 2017 ATS Advanced Telematic Systems GmbH
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */

package com.advancedtelematic.ota.deviceregistry.db

import com.advancedtelematic.libats.data.DataType.Namespace
import com.advancedtelematic.ota.deviceregistry.data.PackageId

trait ColumnTypes {
  import slick.jdbc.MySQLProfile.api._
  protected implicit val namespaceColumnType =
    MappedColumnType.base[Namespace, String](_.get, Namespace.apply)

  protected case class LiftedPackageId(name: Rep[PackageId.Name], version: Rep[PackageId.Version])

  protected implicit object LiftedPackageShape
      extends CaseClassShape(LiftedPackageId.tupled, (p: (PackageId.Name, PackageId.Version)) => PackageId(p._1, p._2))
}
