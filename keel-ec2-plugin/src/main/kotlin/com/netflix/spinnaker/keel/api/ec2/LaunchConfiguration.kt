/*
 *
 * Copyright 2019 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */
package com.netflix.spinnaker.keel.api.ec2

data class LaunchConfiguration(
  val imageId: String,
  val appVersion: String?,
  val baseImageVersion: String?,
  val instanceType: String,
  val ebsOptimized: Boolean = false,
  val iamRole: String,
  val keyPair: String,
  val instanceMonitoring: Boolean = false,
  val ramdiskId: String? = null
) {
  companion object {
    // TODO (lpollo): make these configurable?
    fun defaultKeyPairTemplateFor(account: String) = "nf-keypair-$account-{{region}}"
    fun defaultKeyPairFor(account: String, region: String) = "nf-keypair-$account-$region"
    fun defaultIamRoleFor(application: String) = "${application}InstanceProfile"
  }
}
