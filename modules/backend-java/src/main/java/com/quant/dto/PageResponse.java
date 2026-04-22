package com.quant.dto;

import java.util.List;

/**
 * 分页响应DTO
 * 统一的分页数据返回格式
 *
 * @param <T> 数据项类型
 */
public class PageResponse<T> {

    private List<T> list;
    private long total;
    private int page;
    private int size;
    private int totalPages;

    public PageResponse() {}

    public PageResponse(List<T> list, long total, int page, int size) {
        this.list = list;
        this.total = total;
        this.page = page;
        this.size = size;
        this.totalPages = size > 0 ? (int) Math.ceil((double) total / size) : 0;
    }

    public static <T> PageResponse<T> of(List<T> list, long total, int page, int size) {
        return new PageResponse<>(list, total, page, size);
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), 0, page, size);
    }

    // Getters and Setters
    public List<T> getList() { return list; }
    public void setList(List<T> list) { this.list = list; }
    public long getTotal() { return total; }
    public void setTotal(long total) { this.total = total; }
    public int getPage() { return page; }
    public void setPage(int page) { this.page = page; }
    public int getSize() { return size; }
    public void setSize(int size) { this.size = size; }
    public int getTotalPages() { return totalPages; }
    public void setTotalPages(int totalPages) { this.totalPages = totalPages; }
}
