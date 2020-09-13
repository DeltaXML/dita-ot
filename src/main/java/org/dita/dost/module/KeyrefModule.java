/*
 * This file is part of the DITA Open Toolkit project.
 *
 * Copyright 2010 IBM Corporation
 *
 * See the accompanying LICENSE file for applicable license.

 */
package org.dita.dost.module;

import net.sf.saxon.event.PipelineConfiguration;
import net.sf.saxon.event.Receiver;
import net.sf.saxon.om.FingerprintedQName;
import net.sf.saxon.om.NodeInfo;
import net.sf.saxon.s9api.*;
import net.sf.saxon.s9api.streams.Steps;
import net.sf.saxon.serialize.SerializationProperties;
import net.sf.saxon.trans.UncheckedXPathException;
import net.sf.saxon.trans.XPathException;
import net.sf.saxon.type.BuiltInAtomicType;
import org.dita.dost.exception.DITAOTException;
import org.dita.dost.module.reader.TempFileNameScheme;
import org.dita.dost.pipeline.AbstractPipelineInput;
import org.dita.dost.pipeline.AbstractPipelineOutput;
import org.dita.dost.reader.KeyrefReader;
import org.dita.dost.util.DelayConrefUtils;
import org.dita.dost.util.Job;
import org.dita.dost.util.KeyDef;
import org.dita.dost.util.KeyScope;
import org.dita.dost.writer.ConkeyrefFilter;
import org.dita.dost.writer.KeyrefPaser;
import org.dita.dost.writer.TopicFragmentFilter;
import org.xml.sax.XMLFilter;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.util.stream.Collectors.toMap;
import static net.sf.saxon.expr.parser.ExplicitLocation.UNKNOWN_LOCATION;
import static net.sf.saxon.s9api.streams.Predicates.*;
import static net.sf.saxon.s9api.streams.Steps.ancestorOrSelf;
import static net.sf.saxon.s9api.streams.Steps.attribute;
import static org.dita.dost.util.Configuration.configuration;
import static org.dita.dost.util.Constants.*;
import static org.dita.dost.util.Job.FileInfo;
import static org.dita.dost.util.Job.KEYDEF_LIST_FILE;
import static org.dita.dost.util.URLUtils.*;

/**
 * Keyref ModuleElem.
 */
final class KeyrefModule extends AbstractPipelineModuleImpl {

    private TempFileNameScheme tempFileNameScheme;
    /**
     * Delayed conref utils.
     */
    private DelayConrefUtils delayConrefUtils;
    private String transtype;
    final Set<URI> normalProcessingRole = new HashSet<>();
    final Map<URI, Integer> usage = new HashMap<>();
    private TopicFragmentFilter topicFragmentFilter;

    @Override
    public void setJob(final Job job) {
        super.setJob(job);
        try {
            tempFileNameScheme = (TempFileNameScheme) Class.forName(job.getProperty("temp-file-name-scheme")).newInstance();
        } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
        tempFileNameScheme.setBaseDir(job.getInputDir());
    }

    /**
     * Entry point of KeyrefModule.
     *
     * @param input Input parameters and resources.
     * @return null
     * @throws DITAOTException exception
     */
    @Override
    public AbstractPipelineOutput execute(final AbstractPipelineInput input)
            throws DITAOTException {
        if (fileInfoFilter == null) {
            fileInfoFilter = f -> f.format == null || f.format.equals(ATTR_FORMAT_VALUE_DITA) || f.format.equals(ATTR_FORMAT_VALUE_DITAMAP);
        }
        final Collection<FileInfo> fis = job.getFileInfo(fileInfoFilter).stream()
                .filter(f -> f.hasKeyref)
                .collect(Collectors.toSet());
        if (!fis.isEmpty()) {
            try {
                final String cls = Optional
                        .ofNullable(job.getProperty("temp-file-name-scheme"))
                        .orElse(configuration.get("temp-file-name-scheme"));
                tempFileNameScheme = (TempFileNameScheme) Class.forName(cls).newInstance();
            } catch (InstantiationException | IllegalAccessException | ClassNotFoundException e) {
                throw new RuntimeException(e);
            }
            tempFileNameScheme.setBaseDir(job.getInputDir());
            initFilters();

            // Read start map
            final KeyrefReader reader = new KeyrefReader();
            reader.setLogger(logger);
            reader.setXmlUtils(xmlUtils);
            final Job.FileInfo in = job.getFileInfo(fi -> fi.isInput).iterator().next();
            final URI mapFile = in.uri;
            final XdmNode doc = readMap(in);
            logger.info("Reading " + job.tempDirURI.resolve(mapFile).toString());
            reader.read(job.tempDirURI.resolve(mapFile), doc);

            final KeyScope startScope = reader.getKeyDefinition();

            // Read resources maps
            final Collection<FileInfo> resourceFis = job.getFileInfo(fi -> fi.isInputResource && Objects.equals(fi.format, ATTR_FORMAT_VALUE_DITAMAP));
            final KeyScope rootScope = resourceFis.stream()
                    .map(fi -> {
                        try {
                            final XdmNode d = readMap(fi);
                            logger.info("Reading " + job.tempDirURI.resolve(fi.uri).toString());
                            final KeyrefReader r = new KeyrefReader();
                            r.setLogger(logger);
                            r.read(job.tempDirURI.resolve(fi.uri), d);
                            final KeyScope s = r.getKeyDefinition();
                            writeMap(fi, d);
                            return s;
                        } catch (DITAOTException e) {
                            throw new RuntimeException(e);
                        }
                    })
                    .reduce(startScope, KeyScope::merge);
            final List<ResolveTask> jobs = collectProcessingTopics(in, resourceFis, rootScope, doc);
//            writeMap(in, doc);

            transtype = input.getAttribute(ANT_INVOKER_EXT_PARAM_TRANSTYPE);
            if (transtype.equals(INDEX_TYPE_ECLIPSEHELP)) {
                delayConrefUtils = new DelayConrefUtils();
                delayConrefUtils.setJob(job);
                delayConrefUtils.setLogger(logger);
            } else {
                delayConrefUtils = null;
            }
            for (final ResolveTask r : jobs) {
                if (r.out != null) {
                    processFile(r);
                }
            }
            for (final ResolveTask r : jobs) {
                if (r.out == null) {
                    processFile(r);
                }
            }

            // Store job configuration updates
            for (final URI file : normalProcessingRole) {
                final FileInfo f = job.getFileInfo(file);
                if (f != null) {
                    f.isResourceOnly = false;
                    job.add(f);
                }
            }

            try {
                job.write();
            } catch (final IOException e) {
                throw new DITAOTException("Failed to store job state: " + e.getMessage(), e);
            }
        }
        return null;
    }

    private void initFilters() {
        topicFragmentFilter = new TopicFragmentFilter(ATTRIBUTE_NAME_CONREF, ATTRIBUTE_NAME_CONREFEND);
    }

    /**
     * Collect topics for key reference processing and modify map to reflect new file names.
     */
    private List<ResolveTask> collectProcessingTopics(final FileInfo map,
                                                      final Collection<FileInfo> fis,
                                                      final KeyScope rootScope,
                                                      final XdmNode doc) throws DITAOTException {
        assert doc.getNodeKind() == XdmNodeKind.DOCUMENT;
        final List<ResolveTask> res = new ArrayList<>();
        res.add(new ResolveTask(rootScope, map, null));

        try {
            final URI file = job.tempDirURI.resolve(map.uri);
            final Destination destination = job.getStore().getDestination(file);
            final PipelineConfiguration pipe = doc.getUnderlyingNode().getConfiguration().makePipelineConfiguration();
            final Receiver receiver = destination.getReceiver(pipe, new SerializationProperties());
            receiver.open();
            receiver.startDocument(0);

            // Collect topics from map and rewrite topicrefs for duplicates
            walkMap(map, doc.select(Steps.child().first()).asNode(), Collections.singletonList(rootScope), res, receiver);

            receiver.endDocument();
            receiver.close();
//            job.getStore().writeDocument(destination., file);
        } catch (final IOException | SaxonApiException | XPathException e) {
            throw new DITAOTException("Failed to write map: " + e.getMessage(), e);
        }

        // Collect topics not in map and map itself
        for (final FileInfo f : fis) {
            if (!usage.containsKey(f.uri)) {
                res.add(processTopic(f, rootScope, f.isResourceOnly));
            }
        }

        final List<ResolveTask> deduped = removeDuplicateResolveTargets(res);
        if (fileInfoFilter != null) {
            return adjustResourceRenames(deduped.stream()
                    .filter(rs -> fileInfoFilter.test(rs.in))
                    .collect(Collectors.toList()));
        } else {
            return adjustResourceRenames(deduped);
        }
    }

    /**
     * Remove duplicate sources within the same scope
     */
    private List<ResolveTask> removeDuplicateResolveTargets(List<ResolveTask> renames) {
        return renames.stream()
                .collect(Collectors.groupingBy(
                        rt -> rt.scope,
                        Collectors.toMap(
                                rt -> rt.in.uri,
                                Function.identity(),
                                (rt1, rt2) -> rt1
                        )
                )).values().stream()
                .flatMap(m -> m.values().stream())
                .collect(Collectors.toList());
    }

    /**
     * Adjust key targets per rewrites
     */
    List<ResolveTask> adjustResourceRenames(final List<ResolveTask> renames) {
        final Map<KeyScope, List<ResolveTask>> scopes = renames.stream().collect(Collectors.groupingBy(rt -> rt.scope));

        final List<ResolveTask> res = new ArrayList<>();
        for (final Map.Entry<KeyScope, List<ResolveTask>> group : scopes.entrySet()) {
            final KeyScope scope = group.getKey();
            final List<ResolveTask> tasks = group.getValue();
            final Map<URI, URI> rewrites = tasks.stream()
                    // FIXME this should be filtered out earlier
                    .filter(t -> t.out != null)
                    .collect(toMap(
                            t -> t.in.uri,
                            t -> t.out.uri
                    ));
            final KeyScope resScope = rewriteScopeTargets(scope, rewrites);
            tasks.stream().map(t -> new ResolveTask(resScope, t.in, t.out)).forEach(res::add);
        }

        return res;
    }

    KeyScope rewriteScopeTargets(KeyScope scope, Map<URI, URI> rewrites) {
        final Map<String, KeyDef> newKeys = new HashMap<>();
        for (Map.Entry<String, KeyDef> key : scope.keyDefinition.entrySet()) {
            final KeyDef oldKey = key.getValue();
            URI href = oldKey.href;
            if (href != null && rewrites.containsKey(stripFragment(href))) {
                href = setFragment(rewrites.get(stripFragment(href)), href.getFragment());
            }
            final KeyDef newKey = new KeyDef(oldKey.keys, href, oldKey.scope, oldKey.format, oldKey.source, oldKey.element);
            newKeys.put(key.getKey(), newKey);
        }
        return new KeyScope(scope.id, scope.name,
                newKeys,
                scope.childScopes.stream()
                        .map(c -> rewriteScopeTargets(c, rewrites))
                        .collect(Collectors.toList()));
    }


    /**
     * Tuple class for key reference processing info.
     */
    static class ResolveTask {
        final KeyScope scope;
        final FileInfo in;
        final FileInfo out;

        ResolveTask(final KeyScope scope, final FileInfo in, final FileInfo out) {
            assert scope != null;
            this.scope = scope;
            assert in != null;
            this.in = in;
            this.out = out;
        }
    }

    private QName getReferenceAttribute(final XdmNode elem) {
        assert elem.getNodeKind() == XdmNodeKind.ELEMENT;

        QName rewriteAttrName = new QName(ATTRIBUTE_NAME_COPY_TO);
        if (elem.getAttributeValue(rewriteAttrName) != null) {
            return rewriteAttrName;
        }

        rewriteAttrName = new QName(ATTRIBUTE_NAME_HREF);
        if (elem.getAttributeValue(rewriteAttrName) != null) {
            return rewriteAttrName;
        }

        if (SUBMAP.matches(elem)) {
            rewriteAttrName = new QName(DITA_OT_NS_PREFIX, DITA_OT_NS, "orig-href");
            if (elem.getAttributeValue(rewriteAttrName) != null) {
                return rewriteAttrName;
            }
        }

        return null;
    }

    /**
     * Recursively walk map and process topics that have keyrefs.
     */
    void walkMap(final FileInfo map,
                 final XdmNode elem,
                 final List<KeyScope> scope,
                 final List<ResolveTask> res,
                 final Receiver receiver) throws XPathException {
        assert elem.getNodeKind() == XdmNodeKind.ELEMENT;

        final NodeInfo ni = elem.getUnderlyingNode();
        receiver.startElement(
                new FingerprintedQName(ni.getPrefix(), ni.getURI(), ni.getLocalPart()),
                ni.getSchemaType(),
                ni.saveLocation(),
                0);

        final List<KeyScope> ss = elem.attribute(ATTRIBUTE_NAME_KEYSCOPE) != null
                ? Stream.of(elem.attribute(ATTRIBUTE_NAME_KEYSCOPE).trim().split("\\s+"))
                .flatMap(keyscope -> scope.stream().map(s -> s.getChildScope(keyscope)))
                .collect(Collectors.toList())
                : scope;
        final QName rewriteAttrName = getReferenceAttribute(elem);
        final boolean isResourceOnly = isResourceOnly(elem);

        elem.select(attribute(not(hasLocalName(ATTRIBUTE_NAME_KEYREF)))).forEach(attr -> {
            try {
                if (Objects.equals(attr.getNodeName(), rewriteAttrName)) {
                    String hrefNode = attr.getStringValue();
                    for (final KeyScope s : ss) {
                        final URI href = stripFragment(map.uri.resolve(attr.getStringValue()));
                        final FileInfo fi = job.getFileInfo(href);
                        if (fi != null && fi.hasKeyref) {
                            final int count = usage.getOrDefault(fi.uri, 0);
                            final Optional<ResolveTask> existing = res.stream()
                                    .filter(rt -> rt.scope.equals(s) && rt.in.uri.equals(fi.uri))
                                    .findAny();
                            if (count != 0 && existing.isPresent()) {
                                final ResolveTask resolveTask = existing.get();
                                if (resolveTask.out != null) {
                                    final URI value = tempFileNameScheme.generateTempFileName(resolveTask.out.result);
                                    hrefNode = value.toString();
                                }
                            } else {
                                final ResolveTask resolveTask = processTopic(fi, s, isResourceOnly);
                                res.add(resolveTask);
                                final Integer used = usage.get(fi.uri);
                                if (used > 1) {
                                    final URI value = tempFileNameScheme.generateTempFileName(resolveTask.out.result);
                                    fixKeyDefRefs(s, fi.uri, value);
                                    hrefNode = value.toString();
                                }
                            }
                        }
                    }
                    final NodeInfo ani = attr.getUnderlyingNode();
                    receiver.attribute(
                            new FingerprintedQName(ani.getPrefix(), ani.getURI(), ani.getLocalPart()),
                            BuiltInAtomicType.STRING,
                            hrefNode,
                            UNKNOWN_LOCATION,
                            0
                    );
                } else {
                    receiver.append(attr.getUnderlyingNode());
                }
            } catch (XPathException e) {
                throw new UncheckedXPathException(e);
            }
        });

        for (final XdmNode c : elem.children(c -> MAP_TOPICREF.matches(c))) {
            walkMap(map, c, ss, res, receiver);
        }

        receiver.endElement();
    }

    /**
     * Fix the key definitions to point to the proper href.
     */
    private void fixKeyDefRefs(final KeyScope scope, final URI original, final URI renamed) {
        for (final KeyDef keyDef : scope.keyDefinition.values()) {
            if (keyDef != null
                    && Objects.equals(keyDef.href, original)
                    && keyDef.keys != null) {
                final String prefix = scope.name + ".";
                for (final String key : keyDef.keys.split("\\s")) {
                    if (key.startsWith(prefix)) {
                        keyDef.href = renamed;
                        break;
                    }
                }
            }
        }
    }

    private boolean isResourceOnly(final XdmNode elem) {
        return elem.select(ancestorOrSelf().where(attributeEq(ATTRIBUTE_NAME_PROCESSING_ROLE, ATTR_PROCESSING_ROLE_VALUE_RESOURCE_ONLY)))
                .findAny()
                .isPresent();
    }

    /**
     * Determine how topic is processed for key reference processing.
     *
     * @return key reference processing
     */
    private ResolveTask processTopic(final FileInfo f, final KeyScope scope, final boolean isResourceOnly) {
        final int increment = isResourceOnly ? 0 : 1;
        final Integer used = usage.containsKey(f.uri) ? usage.get(f.uri) + increment : increment;
        usage.put(f.uri, used);

        if (used > 1) {
            final URI result = addSuffix(f.result, "-" + (used - 1));
            final URI out = tempFileNameScheme.generateTempFileName(result);
            final FileInfo fo = new FileInfo.Builder(f)
                    .uri(out)
                    .result(result)
                    .build();
            // TODO: Should this be added when content is actually generated?
            job.add(fo);
            return new ResolveTask(scope, f, fo);
        } else {
            return new ResolveTask(scope, f, null);
        }
    }

    /**
     * Process key references in a topic. Topic is stored with a new name if it's
     * been processed before.
     */
    private void processFile(final ResolveTask r) {
        final List<XMLFilter> filters = new ArrayList<>();

        final ConkeyrefFilter conkeyrefFilter = new ConkeyrefFilter();
        conkeyrefFilter.setLogger(logger);
        conkeyrefFilter.setJob(job);
        conkeyrefFilter.setKeyDefinitions(r.scope);
        conkeyrefFilter.setCurrentFile(job.tempDirURI.resolve(r.in.uri));
        conkeyrefFilter.setDelayConrefUtils(delayConrefUtils);
        filters.add(conkeyrefFilter);

        filters.add(topicFragmentFilter);

        final KeyrefPaser parser = new KeyrefPaser();
        parser.setLogger(logger);
        parser.setJob(job);
        parser.setKeyDefinition(r.scope);
        parser.setCurrentFile(job.tempDirURI.resolve(r.in.uri));
        filters.add(parser);

        try {
            logger.debug("Using " + (r.scope.name != null ? r.scope.name + " scope" : "root scope"));
            if (r.out != null) {
                logger.info("Processing " + job.tempDirURI.resolve(r.in.uri) +
                        " to " + job.tempDirURI.resolve(r.out.uri));
                job.getStore().transform(new File(job.tempDir, r.in.file.getPath()).toURI(),
                        new File(job.tempDir, r.out.file.getPath()).toURI(),
                        filters);
            } else {
                logger.info("Processing " + job.tempDirURI.resolve(r.in.uri));
                job.getStore().transform(new File(job.tempDir, r.in.file.getPath()).toURI(),
                        filters);
            }
            // validate resource-only list
            normalProcessingRole.addAll(parser.getNormalProcessingRoleTargets());
        } catch (final DITAOTException e) {
            logger.error("Failed to process key references: " + e.getMessage(), e);
        }
    }

    /**
     * Add key definition to job configuration
     *
     * @param keydefs key defintions to add
     */
    private void writeKeyDefinition(final Map<String, KeyDef> keydefs) {
        try {
            KeyDef.writeKeydef(new File(job.tempDir, KEYDEF_LIST_FILE), keydefs.values());
        } catch (final DITAOTException e) {
            logger.error("Failed to write key definition file: " + e.getMessage(), e);
        }
    }

    private XdmNode readMap(final FileInfo input) throws DITAOTException {
        try {
            final URI in = job.tempDirURI.resolve(input.uri);
            return job.getStore().getImmutableNode(in);
        } catch (final Exception e) {
            throw new DITAOTException("Failed to parse map: " + e.getMessage(), e);
        }
    }

    private void writeMap(final FileInfo in, final XdmNode doc) throws DITAOTException {
        try {
            final URI file = job.tempDirURI.resolve(in.uri);
//            doc.setDocumentURI(file.toString());
            job.getStore().writeDocument(doc, file);
        } catch (final IOException e) {
            throw new DITAOTException("Failed to write map: " + e.getMessage(), e);
        }
    }


}
