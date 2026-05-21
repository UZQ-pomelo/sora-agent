package com.sora.sora_agent.common;



import java.io.Serializable;

/**
 * 通用请求基类，提供分页等常见请求字段。
 *
 * 具体的业务请求 DTO 可继承此类，以复用分页字段和基本校验逻辑。
 * 非分页场景可直接使用具体的 DTO 类，无需继承。
 */
public class BaseRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 默认页码 */
    private static final int DEFAULT_PAGE_NUM = 1;

    /** 默认每页大小 */
    private static final int DEFAULT_PAGE_SIZE = 10;

    /** 最大每页大小，防止恶意拉取全量数据 */
    private static final int MAX_PAGE_SIZE = 100;

    /** 当前页码，从 1 开始 */
    private int pageNum = DEFAULT_PAGE_NUM;

    /** 每页记录数 */
    private int pageSize = DEFAULT_PAGE_SIZE;

    /**
     * 获取当前页码。
     *
     * @return 页码，最小为 1
     */
    public int getPageNum() {
        return pageNum;
    }

    /**
     * 设置当前页码，小于 1 时自动修正为 1。
     *
     * @param pageNum 页码
     */
    public void setPageNum(int pageNum) {
        this.pageNum = Math.max(pageNum, 1);
    }

    /**
     * 获取每页记录数。
     *
     * @return 每页大小
     */
    public int getPageSize() {
        return pageSize;
    }

    /**
     * 设置每页大小，超出范围时自动修正到 [{@code 1}, {@code 100}] 区间。
     *
     * @param pageSize 每页记录数
     */
    public void setPageSize(int pageSize) {
        this.pageSize = Math.min(Math.max(pageSize, 1), MAX_PAGE_SIZE);
    }
}
