package cn.xiangyu.server.auth;

public interface SmsSender {
    void sendLoginCode(String phone, String code);
}
