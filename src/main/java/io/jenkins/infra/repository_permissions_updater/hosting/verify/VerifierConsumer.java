package io.jenkins.infra.repository_permissions_updater.hosting.verify;

import io.jenkins.infra.repository_permissions_updater.hosting.model.HostingRequest;
import io.jenkins.infra.repository_permissions_updater.hosting.model.VerificationMessage;

import java.util.HashSet;
import java.util.function.BiConsumer;

interface VerifierConsumer extends BiConsumer<HostingRequest, HashSet<VerificationMessage>> {
}
