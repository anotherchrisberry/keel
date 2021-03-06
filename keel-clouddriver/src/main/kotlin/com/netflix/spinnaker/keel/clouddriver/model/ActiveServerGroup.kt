package com.netflix.spinnaker.keel.clouddriver.model

import com.fasterxml.jackson.annotation.JsonAlias
import com.fasterxml.jackson.annotation.JsonCreator
import com.netflix.spinnaker.keel.api.Capacity
import com.netflix.spinnaker.keel.model.Moniker

// todo eb: this should be more general so that it works for all server groups, not just ec2
data class ActiveServerGroup(
  val name: String,
  val region: String,
  val zones: Set<String>,
  val image: ActiveServerGroupImage,
  val launchConfig: LaunchConfig,
  val asg: AutoScalingGroup,
  val vpcId: String,
  val targetGroups: Set<String>,
  val loadBalancers: Set<String>,
  val capacity: Capacity,
  val cloudProvider: String,
  val securityGroups: Set<String>,
  val accountName: String,
  val moniker: Moniker,
  val buildInfo: BuildInfo? = null
)

data class ActiveServerGroupImage(
  val imageId: String,
  val appVersion: String?,
  val baseImageVersion: String?
) {
  @JsonCreator
  constructor(
    imageId: String,
    tags: List<Map<String, Any?>>
  ) : this(
    imageId,
    appVersion = tags.getTag("appversion")?.substringBefore("/"),
    baseImageVersion = tags.getTag("base_ami_version")
  )
}

private fun List<Map<String, Any?>>.getTag(key: String) =
  firstOrNull { it["key"] == key }
    ?.get("value")
    ?.toString()

class RequiredTagMissing(tagName: String, imageId: String) :
  RuntimeException("Required tag \"$tagName\" was not found on AMI $imageId")

data class LaunchConfig(
  val ramdiskId: String?,
  val ebsOptimized: Boolean,
  val imageId: String,
  val instanceType: String,
  val keyName: String,
  val iamInstanceProfile: String,
  val instanceMonitoring: InstanceMonitoring
)

data class AutoScalingGroup(
  val autoScalingGroupName: String,
  val defaultCooldown: Long,
  val healthCheckType: String,
  val healthCheckGracePeriod: Long,
  val suspendedProcesses: Set<SuspendedProcess>,
  val enabledMetrics: Set<String>,
  val tags: Set<Tag>,
  val terminationPolicies: Set<String>,
  val vpczoneIdentifier: String
)

data class SuspendedProcess(
  val processName: String,
  val suspensionReason: String? = null
)

data class Tag(
  val key: String,
  val value: String
)

data class InstanceMonitoring(
  val enabled: Boolean
)

data class BuildInfo(
  @JsonAlias("package_name")
  val packageName: String?
)
