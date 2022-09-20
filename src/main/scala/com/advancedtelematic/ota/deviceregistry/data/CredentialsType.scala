/*
 * Copyright (C) 2017 HERE Global B.V.
 *
 * Licensed under the Mozilla Public License, version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     https://www.mozilla.org/en-US/MPL/2.0/
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * SPDX-License-Identifier: MPL-2.0
 * License-Filename: LICENSE
 */

package com.advancedtelematic.ota.deviceregistry.data

final object CredentialsType extends Enumeration {
  type CredentialsType = Value
  val PEM, OAuthClientCredentials = Value

  implicit val EncoderInstance = io.circe.Encoder.encodeEnumeration(CredentialsType)
  implicit val DecoderInstance = io.circe.Decoder.decodeEnumeration(CredentialsType)
}
