package com.netflix.spinnaker.keel.ec2.resource

import com.fasterxml.jackson.databind.ObjectMapper
import com.netflix.spinnaker.keel.api.Capacity
import com.netflix.spinnaker.keel.api.ClusterDependencies
import com.netflix.spinnaker.keel.api.DeliveryArtifact
import com.netflix.spinnaker.keel.api.Exportable
import com.netflix.spinnaker.keel.api.Resource
import com.netflix.spinnaker.keel.api.ResourceSpec
import com.netflix.spinnaker.keel.api.SubmittedResource
import com.netflix.spinnaker.keel.api.SubnetAwareLocations
import com.netflix.spinnaker.keel.api.SubnetAwareRegionSpec
import com.netflix.spinnaker.keel.api.ec2.ArtifactImageProvider
import com.netflix.spinnaker.keel.api.ec2.ClusterSpec
import com.netflix.spinnaker.keel.api.ec2.Health
import com.netflix.spinnaker.keel.api.ec2.HealthCheckType
import com.netflix.spinnaker.keel.api.ec2.LaunchConfiguration
import com.netflix.spinnaker.keel.api.ec2.Location
import com.netflix.spinnaker.keel.api.ec2.Metric
import com.netflix.spinnaker.keel.api.ec2.Scaling
import com.netflix.spinnaker.keel.api.ec2.ScalingProcess
import com.netflix.spinnaker.keel.api.ec2.ServerGroup
import com.netflix.spinnaker.keel.api.ec2.TerminationPolicy
import com.netflix.spinnaker.keel.api.ec2.byRegion
import com.netflix.spinnaker.keel.api.ec2.moniker
import com.netflix.spinnaker.keel.api.ec2.resolve
import com.netflix.spinnaker.keel.api.id
import com.netflix.spinnaker.keel.api.serviceAccount
import com.netflix.spinnaker.keel.clouddriver.CloudDriverCache
import com.netflix.spinnaker.keel.clouddriver.CloudDriverService
import com.netflix.spinnaker.keel.clouddriver.ResourceNotFound
import com.netflix.spinnaker.keel.clouddriver.model.ActiveServerGroup
import com.netflix.spinnaker.keel.clouddriver.model.Tag
import com.netflix.spinnaker.keel.diff.ResourceDiff
import com.netflix.spinnaker.keel.ec2.CLOUD_PROVIDER
import com.netflix.spinnaker.keel.ec2.SPINNAKER_EC2_API_V1
import com.netflix.spinnaker.keel.ec2.image.ArtifactVersionDeployed
import com.netflix.spinnaker.keel.events.Task
import com.netflix.spinnaker.keel.model.Moniker
import com.netflix.spinnaker.keel.orca.OrcaService
import com.netflix.spinnaker.keel.plugin.TaskLauncher
import com.netflix.spinnaker.keel.plugin.Resolver
import com.netflix.spinnaker.keel.plugin.ResourceHandler
import com.netflix.spinnaker.keel.plugin.SupportedKind
import com.netflix.spinnaker.keel.retrofit.isNotFound
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import org.springframework.context.ApplicationEventPublisher
import retrofit2.HttpException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class ClusterHandler(
  private val cloudDriverService: CloudDriverService,
  private val cloudDriverCache: CloudDriverCache,
  private val orcaService: OrcaService,
  private val taskLauncher: TaskLauncher,
  private val clock: Clock,
  private val publisher: ApplicationEventPublisher,
  objectMapper: ObjectMapper,
  resolvers: List<Resolver<*>>
) : ResourceHandler<ClusterSpec, Map<String, ServerGroup>>(objectMapper, resolvers) {

  override val supportedKind =
    SupportedKind(SPINNAKER_EC2_API_V1, "cluster", ClusterSpec::class.java)

  override suspend fun toResolvedType(resource: Resource<ClusterSpec>): Map<String, ServerGroup> =
    with(resource.spec) {
      resolve().byRegion()
    }

  override suspend fun current(resource: Resource<ClusterSpec>): Map<String, ServerGroup> =
    cloudDriverService
      .getServerGroups(resource)
      .byRegion()

  override suspend fun upsert(
    resource: Resource<ClusterSpec>,
    resourceDiff: ResourceDiff<Map<String, ServerGroup>>
  ): List<Task> =
    coroutineScope {
      resourceDiff
        .toIndividualDiffs()
        .filter { diff -> diff.hasChanges() }
        .map { diff ->
          val desired = diff.desired
          val job = when {
            diff.isCapacityOnly() -> diff.resizeServerGroupJob()
            else -> diff.createServerGroupJob() + resource.spec.deployWith.toOrcaJobProperties()
          }

          log.info("Upserting server group using task: {}", job)

          async {
            taskLauncher.submitJobToOrca(
              resource = resource,
              description = "Upsert server group ${desired.moniker.name} in ${desired.location.account}/${desired.location.region}",
              correlationId = "${resource.id}:${desired.location.region}",
              job = job
            )
          }
        }
        .map { it.await() }
    }

  override suspend fun export(exportable: Exportable): SubmittedResource<ClusterSpec> {
    val serverGroups = cloudDriverService.getServerGroups(
      account = exportable.account,
      moniker = exportable.moniker,
      regions = exportable.regions,
      serviceAccount = exportable.serviceAccount
    )
      .byRegion()

    if (serverGroups.isEmpty()) {
      throw ResourceNotFound("Could not find cluster: ${exportable.moniker.name} " +
        "in account: ${exportable.account} for export")
    }

    val zonesByRegion = serverGroups.map { (region, serverGroup) ->
      region to cloudDriverCache.availabilityZonesBy(
        account = exportable.account,
        vpcId = cloudDriverCache.subnetBy(exportable.account, region, serverGroup.location.subnet).vpcId,
        purpose = serverGroup.location.subnet,
        region = region
      )
    }
      .toMap()

    val subnetAwareRegionSpecs = serverGroups.map { (region, serverGroup) ->
      SubnetAwareRegionSpec(
        name = region,
        availabilityZones =
        if (!serverGroup.location.availabilityZones.containsAll(zonesByRegion[region]
            ?: error("Failed resolving availabilityZones for account: ${exportable.account}, region: $region"))) {
          serverGroup.location.availabilityZones
        } else {
          emptySet()
        }
      )
    }
      .toSet()

    val base = serverGroups.values.first()
    val healthDiff = ResourceDiff(base.health, Health())
    val modifiedHealth = healthDiff.hasChanges()

    val locations = SubnetAwareLocations(
      account = exportable.account,
      subnet = base.location.subnet,
      vpc = base.location.vpc,
      regions = subnetAwareRegionSpecs
    ).withDefaultsOmitted()

    val spec = ClusterSpec(
      moniker = exportable.moniker,
      imageProvider = if (base.buildInfo?.packageName != null) {
        ArtifactImageProvider(
          deliveryArtifact = DeliveryArtifact(name = base.buildInfo.packageName!!))
      } else {
        null
      },
      locations = locations,
      _defaults = ClusterSpec.ServerGroupSpec(
        launchConfiguration = base.launchConfiguration
          .exportSpec(exportable.account, exportable.moniker.app),
        capacity = base.capacity,
        dependencies = base.dependencies,
        health = if (modifiedHealth) {
          base.health.exportSpec()
        } else {
          null
        },
        scaling = if (base.scaling.suspendedProcesses.isEmpty()) {
          null
        } else {
          base.scaling
        },
        tags = base.tags
      ),
      overrides = mutableMapOf()
    )

    spec.generateOverrides(
      exportable.account,
      exportable.moniker.app,
      serverGroups
        .filter { it.value.location.region != base.location.region }
    )

    return SubmittedResource(
      apiVersion = supportedKind.apiVersion,
      kind = supportedKind.kind,
      spec = spec,
      metadata = mapOf("serviceAccount" to exportable.serviceAccount)
    )
  }

  private fun ResourceDiff<Map<String, ServerGroup>>.toIndividualDiffs() =
    desired
      .map { (region, desired) ->
        ResourceDiff(desired, current?.get(region))
      }

  private fun ClusterSpec.generateOverrides(account: String, application: String, serverGroups: Map<String, ServerGroup>) =
    serverGroups.forEach { (region, serverGroup) ->
      val launchSpec = serverGroup.launchConfiguration.exportSpec(account, application)
      val healthSpec = serverGroup.health.exportSpec()
      val dependencies = with(serverGroup.dependencies) {
        ClusterDependencies(
          loadBalancerNames = loadBalancerNames,
          securityGroupNames = securityGroupNames,
          targetGroups = targetGroups
        )
      }

      val launchDiff = ResourceDiff(launchSpec, defaults.launchConfiguration).hasChanges()
      val healthDiff = if (defaults.health == null) {
        ResourceDiff(healthSpec, Health().exportSpec()).hasChanges()
      } else {
        ResourceDiff(healthSpec, defaults.health).hasChanges()
      }
      val dependenciesDiff = ResourceDiff(dependencies, defaults.dependencies).hasChanges()

      if (launchDiff || healthDiff || dependenciesDiff) {
        (overrides as MutableMap)[region] = ClusterSpec.ServerGroupSpec(
          launchConfiguration = if (launchDiff) {
            launchSpec
          } else {
            null
          },
          health = if (healthDiff) {
            healthSpec
          } else {
            null
          },
          dependencies = if (dependenciesDiff) {
            dependencies
          } else {
            null
          }
        )
      }
    }

  override suspend fun <T : ResourceSpec> actuationInProgress(resource: Resource<T>) =
    (resource.spec as ClusterSpec).locations
      .regions
      .map { it.name }
      .any { region ->
        orcaService
          .getCorrelatedExecutions("${resource.id}:$region")
          .isNotEmpty()
      }

  /**
   * @return `true` if the only changes in the diff are to capacity.
   */
  private fun ResourceDiff<ServerGroup>.isCapacityOnly(): Boolean =
    current != null && affectedRootPropertyTypes.all { it == Capacity::class.java }

  private fun ResourceDiff<ServerGroup>.createServerGroupJob(): Map<String, Any?> =
    with(desired) {
      mutableMapOf(
        "application" to moniker.app,
        "credentials" to location.account,
        // the 2hr default timeout sort-of? makes sense in an imperative
        // pipeline world where maybe within 2 hours the environment around
        // the instances will fix itself and the stage will succeed. Since
        // we are telling the red/black strategy to roll back on failure,
        // this will leave us in a position where we will instead keep
        // reattempting to clone the server group because the rollback
        // on failure of instances to come up will leave us in a non
        // converged state...
        "stageTimeoutMs" to Duration.ofMinutes(30).toMillis(),
        "capacity" to mapOf(
          "min" to capacity.min,
          "max" to capacity.max,
          "desired" to capacity.desired
        ),
        "targetHealthyDeployPercentage" to 100, // TODO: any reason to do otherwise?
        "cooldown" to health.cooldown.seconds,
        "enabledMetrics" to health.enabledMetrics,
        "healthCheckType" to health.healthCheckType.name,
        "healthCheckGracePeriod" to health.warmup.seconds,
        "instanceMonitoring" to launchConfiguration.instanceMonitoring,
        "ebsOptimized" to launchConfiguration.ebsOptimized,
        "iamRole" to launchConfiguration.iamRole,
        "terminationPolicies" to health.terminationPolicies.map(TerminationPolicy::name),
        "subnetType" to location.subnet,
        "availabilityZones" to mapOf(
          location.region to location.availabilityZones
        ),
        "keyPair" to launchConfiguration.keyPair,
        "suspendedProcesses" to scaling.suspendedProcesses,
        "securityGroups" to securityGroupIds,
        "stack" to moniker.stack,
        "freeFormDetails" to moniker.detail,
        "tags" to tags,
        "useAmiBlockDeviceMappings" to false, // TODO: any reason to do otherwise?
        "copySourceCustomBlockDeviceMappings" to false, // TODO: any reason to do otherwise?
        "virtualizationType" to "hvm", // TODO: any reason to do otherwise?
        "moniker" to mapOf(
          "app" to moniker.app,
          "stack" to moniker.stack,
          "detail" to moniker.detail,
          "cluster" to moniker.name
        ),
        "amiName" to launchConfiguration.imageId,
        "reason" to "Diff detected at ${clock.instant().iso()}",
        "instanceType" to launchConfiguration.instanceType,
        "type" to "createServerGroup",
        "cloudProvider" to CLOUD_PROVIDER,
        "loadBalancers" to dependencies.loadBalancerNames,
        "targetGroups" to dependencies.targetGroups,
        "account" to location.account
      )
    }
      .also { job ->
        current?.apply {
          job["source"] = mapOf(
            "account" to location.account,
            "region" to location.region,
            "asgName" to moniker.serverGroup
          )
          job["copySourceCustomBlockDeviceMappings"] = true
        }
      }

  private fun ResourceDiff<ServerGroup>.resizeServerGroupJob(): Map<String, Any?> {
    val current = requireNotNull(current) {
      "Current server group must not be null when generating a resize job"
    }
    return mapOf(
      "type" to "resizeServerGroup",
      "capacity" to mapOf(
        "min" to desired.capacity.min,
        "max" to desired.capacity.max,
        "desired" to desired.capacity.desired
      ),
      "cloudProvider" to CLOUD_PROVIDER,
      "credentials" to desired.location.account,
      "moniker" to mapOf(
        "app" to current.moniker.app,
        "stack" to current.moniker.stack,
        "detail" to current.moniker.detail,
        "cluster" to current.moniker.name,
        "sequence" to current.moniker.sequence
      ),
      "region" to current.location.region,
      "serverGroupName" to current.name
    )
  }

  private suspend fun CloudDriverService.getServerGroups(resource: Resource<ClusterSpec>): Iterable<ServerGroup> =
    getServerGroups(
      account = resource.spec.locations.account,
      moniker = resource.spec.moniker,
      regions = resource.spec.locations.regions.map { it.name }.toSet(),
      serviceAccount = resource.serviceAccount
    )
      .also { them ->
        if (them.distinctBy { it.launchConfiguration.appVersion }.size == 1) {
          val appVersion = them.first().launchConfiguration.appVersion
          if (appVersion != null) {
            publisher.publishEvent(ArtifactVersionDeployed(
              resourceId = resource.id,
              artifactVersion = appVersion
            ))
          }
        }
      }

  private suspend fun CloudDriverService.getServerGroups(
    account: String,
    moniker: Moniker,
    regions: Set<String>,
    serviceAccount: String
  ): Iterable<ServerGroup> =
    coroutineScope {
      regions.map {
        async {
          try {
            activeServerGroup(
              serviceAccount,
              moniker.app,
              account,
              moniker.name,
              it,
              CLOUD_PROVIDER
            )
              .toServerGroup()
          } catch (e: HttpException) {
            if (!e.isNotFound) {
              throw e
            }
            null
          }
        }
      }
        .mapNotNull { it.await() }
    }

  /**
   * Transforms CloudDriver response to our server group model.
   */
  private fun ActiveServerGroup.toServerGroup() =
    ServerGroup(
      name = name,
      location = Location(
        account = accountName,
        region = region,
        vpc = cloudDriverCache.networkBy(vpcId).name ?: error("VPC with id $vpcId has no name!"),
        subnet = subnet,
        availabilityZones = zones
      ),
      launchConfiguration = launchConfig.run {
        LaunchConfiguration(
          imageId = imageId,
          appVersion = image.appVersion,
          baseImageVersion = image.baseImageVersion,
          instanceType = instanceType,
          ebsOptimized = ebsOptimized,
          iamRole = iamInstanceProfile,
          keyPair = keyName,
          instanceMonitoring = instanceMonitoring.enabled,
          ramdiskId = ramdiskId.orNull()
        )
      },
      buildInfo = buildInfo,
      capacity = capacity.let { Capacity(it.min, it.max, it.desired) },
      dependencies = ClusterDependencies(
        loadBalancerNames = loadBalancers,
        securityGroupNames = securityGroupNames,
        targetGroups = targetGroups
      ),
      health = Health(
        enabledMetrics = asg.enabledMetrics.map { Metric.valueOf(it) }.toSet(),
        cooldown = asg.defaultCooldown.let(Duration::ofSeconds),
        warmup = asg.healthCheckGracePeriod.let(Duration::ofSeconds),
        healthCheckType = asg.healthCheckType.let { HealthCheckType.valueOf(it) },
        terminationPolicies = asg.terminationPolicies.map { TerminationPolicy.valueOf(it) }.toSet()
      ),
      scaling = Scaling(
        suspendedProcesses = asg.suspendedProcesses.map { ScalingProcess.valueOf(it.processName) }.toSet()
      ),
      tags = asg.tags.associateBy(Tag::key, Tag::value).filterNot { it.key in DEFAULT_TAGS }
    )

  private val ServerGroup.securityGroupIds: Collection<String>
    get() = dependencies
      .securityGroupNames
      // no need to specify these as Orca will auto-assign them, also the application security group
      // gets auto-created so may not exist yet
      .filter { it !in setOf("nf-infrastructure", "nf-datacenter", moniker.app) }
      .map {
        cloudDriverCache.securityGroupByName(location.account, location.region, it).id
      }

  private val ActiveServerGroup.subnet: String
    get() = asg.vpczoneIdentifier.substringBefore(",").let { subnetId ->
      cloudDriverCache
        .subnetBy(subnetId)
        .purpose ?: throw IllegalStateException("Subnet $subnetId has no purpose!")
    }

  private val ActiveServerGroup.securityGroupNames: Set<String>
    get() = securityGroups.map {
      cloudDriverCache.securityGroupById(accountName, region, it).name
    }
      .toSet()

  private fun Instant.iso() =
    atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ISO_DATE_TIME)

  /**
   * Translates a LaunchConfiguration object to a ClusterSpec.LaunchConfigurationSpec with default values omitted for export.
   */
  private fun LaunchConfiguration.exportSpec(account: String, application: String): ClusterSpec.LaunchConfigurationSpec {
    val defaultKeyPair = cloudDriverCache.defaultKeyPairForAccount(account)
    return ClusterSpec.LaunchConfigurationSpec(
      instanceType = instanceType,
      ebsOptimized = if (!ebsOptimized) {
        null
      } else {
        ebsOptimized
      },
      // for the iam role, we compare against a default based on a stable naming convention
      iamRole = if (iamRole == LaunchConfiguration.defaultIamRoleFor(application)) {
        null
      } else {
        iamRole
      },
      // for the key pair, we compare against the currently configured default in clouddriver since there are
      // multiple naming conventions, and handle the case where the default includes a region placeholder
      keyPair = if (defaultKeyPair.contains(REGION_PLACEHOLDER)) {
        if (defaultKeyPair.replace(REGION_PLACEHOLDER, REGION_PATTERN).toRegex().matches(keyPair)) {
          null
        } else {
          keyPair
        }
      } else {
        if (keyPair == defaultKeyPair) {
          null
        } else {
          keyPair
        }
      },
      instanceMonitoring = if (!instanceMonitoring) {
        null
      } else {
        instanceMonitoring
      },
      ramdiskId = ramdiskId
    )
  }

  /**
   * Translates a Health object to a ClusterSpec.HealthSpec with default values omitted for export.
   */
  private fun Health.exportSpec(): ClusterSpec.HealthSpec {
    val defaults by lazy { Health() }
    return ClusterSpec.HealthSpec(
      cooldown = if (cooldown == defaults.cooldown) {
        null
      } else {
        cooldown
      },
      warmup = if (warmup == defaults.warmup) {
        null
      } else {
        warmup
      },
      healthCheckType = if (healthCheckType == defaults.healthCheckType) {
        null
      } else {
        healthCheckType
      },
      enabledMetrics = if (enabledMetrics == defaults.enabledMetrics) {
        null
      } else {
        enabledMetrics
      },
      terminationPolicies = if (terminationPolicies == defaults.terminationPolicies) {
        null
      } else {
        terminationPolicies
      }
    )
  }

  companion object {
    // these tags are auto-applied by CloudDriver so we should not consider them in a diff as they
    // will never be specified as part of desired state
    private val DEFAULT_TAGS = setOf(
      "spinnaker:application",
      "spinnaker:stack",
      "spinnaker:details"
    )

    private const val REGION_PLACEHOLDER = "{{region}}"
    private const val REGION_PATTERN = "([a-z]{2}(-gov)?)-([a-z]+)-\\d"
  }
}

/**
 * Returns `null` if the string is empty or null. Otherwise returns the string.
 */
private fun String?.orNull(): String? = if (isNullOrBlank()) null else this
