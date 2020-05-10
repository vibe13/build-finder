/*
 * Copyright (C) 2017 Red Hat, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.jboss.pnc.build.finder.core;

import static org.jboss.pnc.build.finder.core.AnsiUtils.green;
import static org.jboss.pnc.build.finder.core.AnsiUtils.red;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

import org.apache.commons.codec.binary.Hex;
import org.apache.commons.codec.digest.DigestUtils;
import org.apache.commons.collections4.MultiMapUtils;
import org.jboss.pnc.build.finder.koji.ClientSession;
import org.jboss.pnc.build.finder.koji.KojiBuild;
import org.jboss.pnc.build.finder.koji.KojiLocalArchive;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.redhat.red.build.koji.KojiClientException;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiArchiveType;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildInfo;
import com.redhat.red.build.koji.model.xmlrpc.KojiBuildState;
import com.redhat.red.build.koji.model.xmlrpc.KojiChecksumType;

/**
 * Class providing utility operations for BuildFinder classes
 *
 * @author Jakub Bartecek
 */
public class BuildFinderUtils {
    private static final Logger LOGGER = LoggerFactory.getLogger(BuildFinderUtils.class);

    private List<String> archiveExtensions;

    private DistributionAnalyzer distributionAnalyzer;

    private Map<ChecksumType, String> emptyDigests;

    public BuildFinderUtils(BuildConfig config, DistributionAnalyzer distributionAnalyzer, ClientSession session) {
        this.distributionAnalyzer = distributionAnalyzer;
        loadArchiveExtensions(config, session);
        LOGGER.debug("Archive extensions: {}", green(archiveExtensions));

        emptyDigests = new EnumMap<>(ChecksumType.class);
        emptyDigests.replaceAll((k, v) -> Hex.encodeHexString(DigestUtils.getDigest(k.getAlgorithm()).digest()));

    }

    public boolean shouldSkipChecksum(Checksum checksum, Collection<String> filenames) {
        if (checksum.getValue().equals(emptyDigests.get(checksum.getType()))) {
            LOGGER.warn("Skipped empty digest for files: {}", red(filenames));
            return true;
        }

        List<String> newArchiveExtensions = new ArrayList<>(archiveExtensions.size() + 1);
        newArchiveExtensions.addAll(archiveExtensions);
        newArchiveExtensions.add("rpm");

        if (filenames.stream().noneMatch(filename -> newArchiveExtensions.stream().anyMatch(filename::endsWith))) {
            LOGGER.warn("Skipped due to invalid archive extension for files: {}", red(filenames));
            return false;
        }

        return false;
    }

    public void addArchiveToBuild(KojiBuild build, KojiArchiveInfo archive, Collection<String> filenames) {
        LOGGER.debug(
                "Found build id {} for file {} (checksum {}) matching local files {}",
                build.getBuildInfo().getId(),
                archive.getFilename(),
                archive.getChecksum(),
                filenames);

        Optional<KojiLocalArchive> matchingArchive = build.getArchives()
                .stream()
                .filter(a -> a.getArchive().getArchiveId().equals(archive.getArchiveId()))
                .findFirst();

        if (matchingArchive.isPresent()) {
            LOGGER.debug(
                    "Adding existing archive id {} to build id {} with {} archives and filenames {}",
                    archive.getArchiveId(),
                    archive.getBuildId(),
                    build.getArchives().size(),
                    filenames);

            KojiLocalArchive existingArchive = matchingArchive.get();

            existingArchive.getFilenames().addAll(filenames);
        } else {
            LOGGER.debug(
                    "Adding new archive id {} to build id {} with {} archives and filenames {}",
                    archive.getArchiveId(),
                    archive.getBuildId(),
                    build.getArchives().size(),
                    filenames);

            KojiLocalArchive localArchive = new KojiLocalArchive(
                    archive,
                    filenames,
                    distributionAnalyzer != null ? distributionAnalyzer.getFiles().get(filenames.iterator().next())
                            : Collections.emptySet());
            List<KojiLocalArchive> buildArchives = build.getArchives();

            buildArchives.add(localArchive);

            buildArchives.sort(Comparator.comparing(a -> a.getArchive().getFilename()));
        }
    }

    public void addArchiveWithoutBuild(KojiBuild buildZero, Checksum checksum, Collection<String> filenames) {
        Optional<KojiLocalArchive> matchingArchive = buildZero.getArchives()
                .stream()
                .filter(
                        a -> a.getArchive()
                                .getChecksumType()
                                .equals(KojiChecksumType.valueOf(checksum.getType().getAlgorithm().toLowerCase()))
                                && a.getArchive().getChecksum().equals(checksum.getValue()))
                .findFirst();

        if (matchingArchive.isPresent()) {
            KojiLocalArchive existingArchive = matchingArchive.get();

            LOGGER.debug(
                    "Adding not-found checksum {} to existing archive id {} with filenames {}",
                    existingArchive.getArchive().getChecksum(),
                    existingArchive.getArchive().getArchiveId(),
                    filenames);

            existingArchive.getFilenames().addAll(filenames);
        } else {
            KojiArchiveInfo tmpArchive = new KojiArchiveInfo();

            tmpArchive.setBuildId(0);
            tmpArchive.setFilename("not found");
            tmpArchive.setChecksum(checksum.getValue());
            tmpArchive.setChecksumType(KojiChecksumType.valueOf(checksum.getType().getAlgorithm().toLowerCase()));

            tmpArchive.setArchiveId(-1 * (buildZero.getArchives().size() + 1));

            LOGGER.debug(
                    "Adding not-found checksum {} to new archive id {} with filenames {}",
                    checksum,
                    tmpArchive.getArchiveId(),
                    filenames);

            KojiLocalArchive localArchive = new KojiLocalArchive(
                    tmpArchive,
                    filenames,
                    distributionAnalyzer != null ? distributionAnalyzer.getFiles().get(filenames.iterator().next())
                            : Collections.emptySet());
            List<KojiLocalArchive> buildZeroArchives = buildZero.getArchives();

            buildZeroArchives.add(localArchive);

            buildZeroArchives.sort(Comparator.comparing(a -> a.getArchive().getFilename()));
        }
    }

    public KojiBuild createKojiBuildZero() {
        KojiBuildInfo buildInfo = new KojiBuildInfo();

        buildInfo.setId(0);
        buildInfo.setPackageId(0);
        buildInfo.setBuildState(KojiBuildState.ALL);
        buildInfo.setName("not found");
        buildInfo.setVersion("not found");
        buildInfo.setRelease("not found");

        return new KojiBuild(buildInfo);
    }

    public void addFilesInError(KojiBuild buildZero) {
        if (distributionAnalyzer != null) {
            for (String fileInError : distributionAnalyzer.getFilesInError()) {
                Optional<Checksum> checksum = Checksum.findByType(
                        MultiMapUtils.getValuesAsSet(distributionAnalyzer.getFiles(), fileInError),
                        ChecksumType.md5);

                checksum.ifPresent(
                        cksum -> addArchiveWithoutBuild(buildZero, cksum, Collections.singletonList(fileInError)));
            }
        }
    }

    public void loadArchiveExtensions(BuildConfig config, ClientSession session) {
        LOGGER.debug("Asking server for archive extensions");
        try {
            archiveExtensions = getArchiveExtensionsFromKoji(config, session);
        } catch (KojiClientException e) {
            LOGGER.warn("Getting archive extensions from Koji failed!", e);
            LOGGER.debug("Getting archive extensions from configuration file");
            archiveExtensions = config.getArchiveExtensions();
        }
    }

    public List<String> getArchiveExtensions() {
        return archiveExtensions;
    }

    private List<String> getArchiveExtensionsFromKoji(BuildConfig config, ClientSession session)
            throws KojiClientException {
        Map<String, KojiArchiveType> allArchiveTypesMap = session.getArchiveTypeMap();

        List<String> allArchiveTypes = allArchiveTypesMap.values()
                .stream()
                .map(KojiArchiveType::getName)
                .collect(Collectors.toList());
        List<String> archiveTypes = config.getArchiveTypes();
        List<String> archiveTypesToCheck;

        LOGGER.debug("Archive types: {}", green(archiveTypes));

        if (archiveTypes != null && !archiveTypes.isEmpty()) {
            LOGGER.debug("There are {} supplied Koji archive types: {}", archiveTypes.size(), archiveTypes);
            archiveTypesToCheck = archiveTypes.stream()
                    .filter(allArchiveTypesMap::containsKey)
                    .collect(Collectors.toList());
            LOGGER.debug("There are {} valid supplied Koji archive types: {}", archiveTypes.size(), archiveTypes);
        } else {
            LOGGER.debug("There are {} known Koji archive types: {}", allArchiveTypes.size(), allArchiveTypes);
            LOGGER.warn("Supplied archive types list is empty; defaulting to all known archive types");
            archiveTypesToCheck = allArchiveTypes;
        }

        LOGGER.debug("There are {} Koji archive types to check: {}", archiveTypesToCheck.size(), archiveTypesToCheck);

        List<String> allArchiveExtensions = allArchiveTypesMap.values()
                .stream()
                .map(KojiArchiveType::getExtensions)
                .flatMap(List::stream)
                .collect(Collectors.toList());
        List<String> localArchiveExtensions = config.getArchiveExtensions();
        List<String> archiveExtensionsToCheck;

        if (localArchiveExtensions != null && !localArchiveExtensions.isEmpty()) {
            LOGGER.debug(
                    "There are {} supplied Koji archive extensions: {}",
                    localArchiveExtensions.size(),
                    localArchiveExtensions);
            archiveExtensionsToCheck = localArchiveExtensions.stream()
                    .filter(allArchiveExtensions::contains)
                    .collect(Collectors.toList());
            LOGGER.debug(
                    "There are {} valid supplied Koji archive extensions: {}",
                    localArchiveExtensions.size(),
                    localArchiveExtensions);
        } else {
            LOGGER.debug(
                    "There are {} known Koji archive extensions: {}",
                    allArchiveExtensions.size(),
                    allArchiveExtensions.size());
            LOGGER.warn("Supplied archive extensions list is empty; defaulting to all known archive extensions");
            archiveExtensionsToCheck = allArchiveExtensions;
        }

        return archiveExtensionsToCheck;
    }
}