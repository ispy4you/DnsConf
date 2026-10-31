package com.novibe.common.data_sources;

import com.novibe.common.http.HttpRequestSender;
import com.novibe.common.base_structures.HostsLine;
import com.novibe.common.exception.DnsHttpError;
import com.novibe.common.exception.UserInputException;
import com.novibe.common.util.DataParser;
import com.novibe.common.util.Log;
import com.novibe.common.util.ParallelTasks;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Loads hosts files, so requests need no credentials.
 */
public abstract class ListLoader<T> extends HttpRequestSender {

    private static final int RETRY_ATTEMPTS = 3;

    private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

    protected abstract T toObject(HostsLine hostsLine);

    protected abstract String listType();

    protected abstract Predicate<HostsLine> filterRelatedLines();

    @Override
    protected String requestUrl(String path) {
        return path;
    }

    @Override
    protected Map<String, String> headers() {
        return Map.of();
    }

    @Override
    protected int retryAttempts() {
        return RETRY_ATTEMPTS;
    }

    @Override
    protected Duration retryDelay() {
        return RETRY_DELAY;
    }

    @Override
    protected void reactOnError(DnsHttpError dnsHttpError) {
        throw UserInputException.noStackTrace("Failed to load %s list, response code %s from url: %s"
                .formatted(listType(), dnsHttpError.getCode(), dnsHttpError.getRequestUrl()));
    }

    public List<T> fetchWebsites(List<String> urls) {
        return ParallelTasks.callAll(urls, this::fetchList).stream()
                .flatMap(DataParser::splitByEol)
                .map(String::strip)
                .parallel()
                .filter(line -> !line.isBlank())
                .filter(line -> !DataParser.isComment(line))
                .map(String::toLowerCase)
                .map(DataParser::parseHostsLine)
                .filter(Objects::nonNull)
                .filter(filterRelatedLines())
                .distinct()
                .map(this::toObject)
                .collect(Collectors.toCollection(ArrayList::new));
    }

    private String fetchList(String url) {
        Log.io("Loading %s list from url: %s".formatted(listType(), url));
        return sendRequest(GET, url, null).body();
    }

}
