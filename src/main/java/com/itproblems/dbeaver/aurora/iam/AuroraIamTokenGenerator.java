package com.itproblems.dbeaver.aurora.iam;

import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.ProfileCredentialsProvider;
import software.amazon.awssdk.profiles.ProfileFile;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.rds.RdsUtilities;
import software.amazon.awssdk.utils.SdkAutoCloseable;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Generates RDS IAM auth tokens. Token signing is local; only credential resolution (SSO, STS) touches the network.
 */
public final class AuroraIamTokenGenerator {

    // e.g. mydb.cluster-abc123.us-east-1.rds.amazonaws.com, or *.rds.amazonaws.com.cn for China regions
    private static final Pattern RDS_HOST_REGION = Pattern.compile(
        "\\.([a-z]{2}(?:-gov|-iso[a-z]?)?-[a-z]+-\\d+)\\.rds\\.amazonaws\\.com(?:\\.cn)?$");

    // Cached per profile so SSO / assumed-role credentials are reused until they near expiry.
    private static final Map<String, AwsCredentialsProvider> PROVIDERS = new ConcurrentHashMap<>();

    private AuroraIamTokenGenerator() {
    }

    public static String generateToken(String host, int port, String user, String profile, String region) throws Exception {
        String profileKey = profile == null ? "" : profile.trim();
        return withBundleClassLoader(() -> {
            AwsCredentialsProvider credentials = PROVIDERS.computeIfAbsent(profileKey, AuroraIamTokenGenerator::createProvider);
            try {
                RdsUtilities utilities = RdsUtilities.builder()
                    .credentialsProvider(credentials)
                    .region(Region.of(resolveRegion(host, profileKey, region)))
                    .build();
                return utilities.generateAuthenticationToken(b -> b.hostname(host).port(port).username(user));
            } catch (Exception e) {
                // Drop the cached provider so the next attempt picks up e.g. a fresh `aws sso login`.
                if (PROVIDERS.remove(profileKey, credentials) && credentials instanceof SdkAutoCloseable closeable) {
                    closeable.close();
                }
                throw e;
            }
        });
    }

    static String resolveRegion(String host, String profile, String explicitRegion) {
        if (explicitRegion != null && !explicitRegion.isBlank()) {
            return explicitRegion.trim();
        }
        String fromHost = regionFromHost(host);
        if (fromHost != null) {
            return fromHost;
        }
        DefaultAwsRegionProviderChain.Builder chain = DefaultAwsRegionProviderChain.builder();
        if (!profile.isEmpty()) {
            chain.profileName(profile);
        }
        return chain.build().getRegion().id();
    }

    static String regionFromHost(String host) {
        if (host == null) {
            return null;
        }
        Matcher matcher = RDS_HOST_REGION.matcher(host.trim().toLowerCase());
        return matcher.find() ? matcher.group(1) : null;
    }

    /** Profile names from ~/.aws/config and ~/.aws/credentials, for the UI. */
    public static List<String> listProfiles() {
        try {
            return withBundleClassLoader(() -> ProfileFile.defaultProfileFile().profiles().keySet().stream().sorted().toList());
        } catch (Exception e) {
            return List.of();
        }
    }

    private static AwsCredentialsProvider createProvider(String profile) {
        return profile.isEmpty()
            ? DefaultCredentialsProvider.builder().build()
            : ProfileCredentialsProvider.builder().profileName(profile).build();
    }

    /**
     * The SDK discovers its HTTP client and profile-credential factories through the thread context class loader,
     * which under OSGi is not our bundle's loader.
     */
    private static <T> T withBundleClassLoader(Callable<T> action) throws Exception {
        Thread thread = Thread.currentThread();
        ClassLoader previous = thread.getContextClassLoader();
        thread.setContextClassLoader(AuroraIamTokenGenerator.class.getClassLoader());
        try {
            return action.call();
        } finally {
            thread.setContextClassLoader(previous);
        }
    }
}
