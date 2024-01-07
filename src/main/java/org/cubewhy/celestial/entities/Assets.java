/**
 * Copyright 2024 json.cn
 */
package org.cubewhy.celestial.entities;
import lombok.Data;

import java.util.Date;

/**
 * Auto-generated: 2024-01-07 19:7:0
 *
 * @author json.cn (i@json.cn)
 * @website http://www.json.cn/java2pojo/
 */
@Data
public class Assets {

    private String url;
    private long id;
    private String node_id;
    private String name;
    private String label;
    private Uploader uploader;
    private String content_type;
    private String state;
    private long size;
    private int download_count;
    private Date created_at;
    private Date updated_at;
    private String browser_download_url;

}