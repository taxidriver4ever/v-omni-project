package org.example.vomnimedia.common;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
public class MyResult<T> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private int code;
    private String msg;
    private T data;

    public static <T> MyResult<T> success(T data) {
        MyResult<T> result = new MyResult<>();
        result.setCode(200);
        result.setMsg("操作成功");
        result.setData(data);
        return result;
    }

    public static <T> MyResult<T> success() {
        return success(null);
    }

    public static <T> MyResult<T> error(Integer code, String msg) {
        MyResult<T> result = new MyResult<>();
        result.setCode(code);
        result.setMsg(msg);
        return result;
    }
}
