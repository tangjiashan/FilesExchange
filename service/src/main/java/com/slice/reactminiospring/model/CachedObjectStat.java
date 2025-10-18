package com.slice.reactminiospring.model;


import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;


import java.io.Serializable;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class CachedObjectStat implements Serializable {
    private String object;
    private long size;
    private String etag;
    private String lastModified;
}
