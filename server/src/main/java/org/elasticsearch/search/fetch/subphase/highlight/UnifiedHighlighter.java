/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.elasticsearch.search.fetch.subphase.highlight;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.IndexOptions;
import org.apache.lucene.search.IndexSearcher;
import org.apache.lucene.search.highlight.Encoder;
import org.apache.lucene.search.uhighlight.BoundedBreakIteratorScanner;
import org.apache.lucene.search.uhighlight.CustomPassageFormatter;
import org.apache.lucene.search.uhighlight.CustomSeparatorBreakIterator;
import org.apache.lucene.search.uhighlight.CustomUnifiedHighlighter;
import org.apache.lucene.search.uhighlight.PassageFormatter;
import org.apache.lucene.search.uhighlight.Snippet;
import org.apache.lucene.search.uhighlight.UnifiedHighlighter.OffsetSource;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.CollectionUtil;
import org.elasticsearch.common.Strings;
import org.elasticsearch.common.text.Text;
import org.elasticsearch.index.IndexSettings;
import org.elasticsearch.index.mapper.DocumentMapper;
import org.elasticsearch.index.mapper.MappedFieldType;
import org.elasticsearch.search.fetch.FetchPhaseExecutionException;
import org.elasticsearch.search.fetch.FetchSubPhase;
import org.elasticsearch.search.fetch.FetchSubPhase.HitContext;
import org.elasticsearch.search.internal.SearchContext;

import java.io.IOException;
import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.apache.lucene.search.uhighlight.CustomUnifiedHighlighter.MULTIVAL_SEP_CHAR;

public class UnifiedHighlighter implements Highlighter {
    @Override
    public boolean canHighlight(MappedFieldType fieldType) {
        return true;
    }
    
    @Override
    public HighlightField highlight(HighlighterContext highlighterContext) {
        MappedFieldType fieldType = highlighterContext.fieldType;
        SearchContextHighlight.Field field = highlighterContext.field;
        SearchContext context = highlighterContext.context;
        FetchSubPhase.HitContext hitContext = highlighterContext.hitContext;
        Encoder encoder = field.fieldOptions().encoder().equals("html") ? HighlightUtils.Encoders.HTML : HighlightUtils.Encoders.DEFAULT;
        final int maxAnalyzedOffset = context.indexShard().indexSettings().getHighlightMaxAnalyzedOffset();

        List<Snippet> snippets = new ArrayList<>();
        int numberOfFragments;
        try {

            final Analyzer analyzer = getAnalyzer(context.mapperService().documentMapper(hitContext.hit().getType()), fieldType,
                    hitContext);
            List<Object> fieldValues = loadFieldValues(fieldType, field, context, hitContext);
            if (fieldValues.size() == 0) {
                return null;
            }
            final PassageFormatter passageFormatter = getPassageFormatter(hitContext, field, encoder);
            final IndexSearcher searcher = new IndexSearcher(hitContext.reader());
            final CustomUnifiedHighlighter highlighter;
            final String fieldValue = mergeFieldValues(fieldValues, MULTIVAL_SEP_CHAR);
            final OffsetSource offsetSource = getOffsetSource(fieldType);
            if ((offsetSource == OffsetSource.ANALYSIS) && (fieldValue.length() > maxAnalyzedOffset)) {
                throw new IllegalArgumentException(
                    "The length of [" + highlighterContext.fieldName + "] field of [" + hitContext.hit().getId() +
                        "] doc of [" + context.indexShard().shardId().getIndexName() + "] index " + "has exceeded [" +
                        maxAnalyzedOffset + "] - maximum allowed to be analyzed for highlighting. " +
                        "This maximum can be set by changing the [" + IndexSettings.MAX_ANALYZED_OFFSET_SETTING.getKey() +
                        "] index level setting. " + "For large texts, indexing with offsets or term vectors is recommended!");
            }
            if (field.fieldOptions().numberOfFragments() == 0) {
                // we use a control char to separate values, which is the only char that the custom break iterator
                // breaks the text on, so we don't lose the distinction between the different values of a field and we
                // get back a snippet per value
                CustomSeparatorBreakIterator breakIterator = new CustomSeparatorBreakIterator(MULTIVAL_SEP_CHAR);
                highlighter = new CustomUnifiedHighlighter(searcher, analyzer, offsetSource, passageFormatter,
                    field.fieldOptions().boundaryScannerLocale(), breakIterator, fieldValue, field.fieldOptions().noMatchSize());
                numberOfFragments = fieldValues.size(); // we are highlighting the whole content, one snippet per value
            } else {
                //using paragraph separator we make sure that each field value holds a discrete passage for highlighting
                BreakIterator bi = getBreakIterator(field);
                highlighter = new CustomUnifiedHighlighter(searcher, analyzer, offsetSource, passageFormatter,
                    field.fieldOptions().boundaryScannerLocale(), bi,
                    fieldValue, field.fieldOptions().noMatchSize());
                numberOfFragments = field.fieldOptions().numberOfFragments();
            }

            if (field.fieldOptions().requireFieldMatch()) {
                final String fieldName = highlighterContext.fieldName;
                highlighter.setFieldMatcher((name) -> fieldName.equals(name));
            } else {
                highlighter.setFieldMatcher((name) -> true);
            }

            Snippet[] fieldSnippets = highlighter.highlightField(highlighterContext.fieldName,
                highlighterContext.query, hitContext.docId(), numberOfFragments);
            for (Snippet fieldSnippet : fieldSnippets) {
                if (Strings.hasText(fieldSnippet.getText())) {
                    snippets.add(fieldSnippet);
                }
            }
        } catch (IOException e) {
            throw new FetchPhaseExecutionException(context,
                "Failed to highlight field [" + highlighterContext.fieldName + "]", e);
        }

        if (field.fieldOptions().scoreOrdered()) {
            //let's sort the snippets by score if needed
            CollectionUtil.introSort(snippets, (o1, o2) -> Double.compare(o2.getScore(), o1.getScore()));
        }

        String[] fragments = new String[snippets.size()];
        for (int i = 0; i < fragments.length; i++) {
            fragments[i] = snippets.get(i).getText();
        }

        if (fragments.length > 0) {
            return new HighlightField(highlighterContext.fieldName, Text.convertFromStringArray(fragments));
        }
        return null;
    }

    protected PassageFormatter getPassageFormatter(HitContext hitContext, SearchContextHighlight.Field field, Encoder encoder) {
        CustomPassageFormatter passageFormatter = new CustomPassageFormatter(field.fieldOptions().preTags()[0],
            field.fieldOptions().postTags()[0], encoder);
        return passageFormatter;
    }

    
    protected Analyzer getAnalyzer(DocumentMapper docMapper, MappedFieldType type, HitContext hitContext) {
        return HighlightUtils.getAnalyzer(docMapper, type);
    }
    
    protected List<Object> loadFieldValues(MappedFieldType fieldType, SearchContextHighlight.Field field, SearchContext context,
            FetchSubPhase.HitContext hitContext) throws IOException {
        List<Object> fieldValues = HighlightUtils.loadFieldValues(field, fieldType, context, hitContext);
        fieldValues = fieldValues.stream()
            .map((s) -> convertFieldValue(fieldType, s))
            .collect(Collectors.toList());
        return fieldValues;
    }

    protected BreakIterator getBreakIterator(SearchContextHighlight.Field field) {
        final SearchContextHighlight.FieldOptions fieldOptions = field.fieldOptions();
        final Locale locale =
            fieldOptions.boundaryScannerLocale() != null ? fieldOptions.boundaryScannerLocale() :
                Locale.ROOT;
        final HighlightBuilder.BoundaryScannerType type =
            fieldOptions.boundaryScannerType()  != null ? fieldOptions.boundaryScannerType() :
                HighlightBuilder.BoundaryScannerType.SENTENCE;
        int maxLen = fieldOptions.fragmentCharSize();
        switch (type) {
            case SENTENCE:
                if (maxLen > 0) {
                    return BoundedBreakIteratorScanner.getSentence(locale, maxLen);
                }
                return BreakIterator.getSentenceInstance(locale);
            case WORD:
                // ignore maxLen
                return BreakIterator.getWordInstance(locale);
            default:
                throw new IllegalArgumentException("Invalid boundary scanner type: " + type.toString());
        }
    }

    protected static String convertFieldValue(MappedFieldType type, Object value) {
        if (value instanceof BytesRef) {
            return type.valueForDisplay(value).toString();
        } else {
            return value.toString();
        }
    }

    protected static String mergeFieldValues(List<Object> fieldValues, char valuesSeparator) {
        //postings highlighter accepts all values in a single string, as offsets etc. need to match with content
        //loaded from stored fields, we merge all values using a proper separator
        String rawValue = Strings.collectionToDelimitedString(fieldValues, String.valueOf(valuesSeparator));
        return rawValue.substring(0, Math.min(rawValue.length(), Integer.MAX_VALUE - 1));
    }

    protected OffsetSource getOffsetSource(MappedFieldType fieldType) {
        if (fieldType.indexOptions() == IndexOptions.DOCS_AND_FREQS_AND_POSITIONS_AND_OFFSETS) {
            return fieldType.storeTermVectors() ? OffsetSource.POSTINGS_WITH_TERM_VECTORS : OffsetSource.POSTINGS;
        }
        if (fieldType.storeTermVectorOffsets()) {
            return OffsetSource.TERM_VECTORS;
        }
        return OffsetSource.ANALYSIS;
    }
}
