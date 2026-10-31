package com.novibe.dns.cloudflare.service;

import com.novibe.common.base_structures.BypassRoute;
import com.novibe.common.exception.DnsApiException;
import com.novibe.common.service.ExcludeRedirectCheckService;
import com.novibe.common.util.Log;
import com.novibe.common.util.ParallelTasks;
import com.novibe.dns.cloudflare.http.CloudflareListClient;
import com.novibe.dns.cloudflare.http.dto.Item;
import com.novibe.dns.cloudflare.http.dto.request.CreateListRequest;
import com.novibe.dns.cloudflare.http.dto.response.CloudflareApiMessage;
import com.novibe.dns.cloudflare.http.dto.response.list.GatewayListDto;
import com.novibe.dns.cloudflare.http.dto.response.list.SingleListApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class ListService {

    private static final String BLOCK_LIST_NAME_PREFIX = "Blocked websites by script";
    private static final String OVERRIDE_LIST_NAME_PREFIX = "Override websites by script";

    private final CloudflareListClient cloudflareListClient;
    private final ExcludeRedirectCheckService excludeRedirectCheckService;
    private final String sessionId;

    public List<GatewayListDto> createNewBlockLists(List<String> websitesToBlock) {

        List<List<Item>> websitesByChunks = cutChunks(websiteAsItem(websitesToBlock));

        Log.common("Total websites count: %s\nPrepared %s chunks of websites list to block."
                .formatted(websitesToBlock.size(), websitesByChunks.size()));

        List<CreateListRequest> createListRequests = mapToBlockListRequests(websitesByChunks);
        return saveNewLists(createListRequests);
    }

    public void omitExcludedOverrides(List<BypassRoute> routes) {
        routes.removeIf(route -> excludeRedirectCheckService.shouldExclude(route.website()));
    }


    public Map<String, List<GatewayListDto>> createNewOverrideLists(List<BypassRoute> routes) {

        Map<String, List<GatewayListDto>> result = new HashMap<>();

        Map<String, List<CreateListRequest>> requests = formOverrideListRequestsByIp(routes);

        for (Map.Entry<String, List<CreateListRequest>> entry : requests.entrySet()) {
            String overrideIp = entry.getKey();
            Log.io("Posting %s override lists for IP: %s" .formatted(entry.getValue().size(), overrideIp));
            List<GatewayListDto> response = saveNewLists(entry.getValue());
            result.put(overrideIp, response);
        }
        return result;
    }

    public void removeOldLists() {
        List<UUID> oldIds = cloudflareListClient.getLists()
                .stream()
                .filter(list -> list.getName().startsWith(BLOCK_LIST_NAME_PREFIX)
                        || list.getName().startsWith(OVERRIDE_LIST_NAME_PREFIX))
                .filter(list -> !sessionId.equals(list.getDescription()))
                .map(GatewayListDto::getId)
                .toList();
        if (oldIds.isEmpty()) {
            Log.common("No lists found to remove");
            return;
        }
        Log.io("Removing " + oldIds.size() + " lists...");
        AtomicInteger counter = new AtomicInteger();
        List<SingleListApiResponse> responses = ParallelTasks.callAll(oldIds, id -> {
            SingleListApiResponse response = cloudflareListClient.deleteListById(id);
            if (response.isSuccess()) {
                Log.progress(counter.incrementAndGet() + "/" + oldIds.size() + " removed");
            }
            return response;
        });

        List<List<CloudflareApiMessage>> errors = responses.stream()
                .filter(response -> !response.isSuccess())
                .map(SingleListApiResponse::getErrors)
                .toList();

        if (!errors.isEmpty()) {
            Log.fail("Failed to remove old lists (%s of %s): %s".formatted(errors.size(), oldIds.size(), errors));
        } else {
            Log.common("\n%s of %s old lists have been removed".formatted(counter, oldIds.size()));
        }
    }

    private List<GatewayListDto> saveNewLists(List<CreateListRequest> createListRequests) {
        AtomicInteger counter = new AtomicInteger();
        List<SingleListApiResponse> responses = ParallelTasks.callAll(createListRequests, request -> {
            SingleListApiResponse response = cloudflareListClient.postList(request);
            if (response.isSuccess()) {
                Log.progress(counter.incrementAndGet() + "/" + createListRequests.size() + " saved");
            }
            return response;
        });

        List<List<CloudflareApiMessage>> errors = responses.stream()
                .filter(response -> !response.isSuccess())
                .map(SingleListApiResponse::getErrors)
                .toList();
        if (!errors.isEmpty()) {
            throw new DnsApiException("Failed to save new lists (%s of %s): %s"
                    .formatted(errors.size(), createListRequests.size(), errors));
        }
        return responses.stream().map(SingleListApiResponse::getResult).toList();
    }

    Map<String, List<CreateListRequest>> formOverrideListRequestsByIp(List<BypassRoute> routes) {
        Map<String, String> mergedWebsiteOnIp = new HashMap<>();
        //Priority of IP is provided by sources order
        for (BypassRoute route : routes) {
            mergedWebsiteOnIp.putIfAbsent(route.website(), route.ip());
        }
        //Group to lists by IP
        Map<String, List<CreateListRequest>> result = new HashMap<>();
        Map<String, List<String>> ipForWebsites = mergedWebsiteOnIp.entrySet().stream()
                .collect(Collectors.groupingBy(Map.Entry::getValue, Collectors.mapping(Map.Entry::getKey, Collectors.toList())));
        for (Map.Entry<String, List<String>> entry : ipForWebsites.entrySet()) {
            List<List<Item>> chunks = cutChunks(websiteAsItem(entry.getValue()));
            List<CreateListRequest> createListRequests = mapToOverrideListRequests(chunks, entry.getKey());
            result.put(entry.getKey(), createListRequests);
        }
        return result;
    }

    private List<CreateListRequest> mapToBlockListRequests(List<List<Item>> chunkedWebsitesList) {
        return mapToListRequests(chunkedWebsitesList, BLOCK_LIST_NAME_PREFIX);
    }

    private List<CreateListRequest> mapToOverrideListRequests(List<List<Item>> chunkedWebsitesList, String ip) {
        return mapToListRequests(chunkedWebsitesList, OVERRIDE_LIST_NAME_PREFIX + " to IP " + ip);
    }

    private List<CreateListRequest> mapToListRequests(List<List<Item>> chunkedWebsitesList, String namePrefix) {
        int chunkNumber = 1;
        ArrayList<CreateListRequest> requests = new ArrayList<>();
        for (List<Item> items : chunkedWebsitesList) {
            CreateListRequest newListRequestDto = CreateListRequest.builder()
                    .name(namePrefix + " " + chunkNumber++)
                    .type("DOMAIN")
                    .items(items)
                    .description(sessionId)
                    .build();
            requests.add(newListRequestDto);
        }
        return requests;
    }


    private List<Item> websiteAsItem(List<String> urlsToBlock) {
        return urlsToBlock.stream().map(Item::new).toList();
    }

    static <T> List<List<T>> cutChunks(List<T> list) {
        final int chunkSize = 1000;
        List<List<T>> chunks = new ArrayList<>();
        if (list.size() <= chunkSize) {
            chunks = List.of(list);

        } else {
            for (int i = 0; i < list.size(); i += chunkSize) {
                chunks.add(list.subList(i, Math.min(i + chunkSize, list.size())));
            }
        }
        return chunks;
    }
}
