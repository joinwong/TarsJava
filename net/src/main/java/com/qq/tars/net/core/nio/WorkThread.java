/**
 * Tencent is pleased to support the open source community by making Tars available.
 *
 * Copyright (C) 2016 THL A29 Limited, a Tencent company. All rights reserved.
 *
 * Licensed under the BSD 3-Clause License (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * https://opensource.org/licenses/BSD-3-Clause
 *
 * Unless required by applicable law or agreed to in writing, software distributed
 * under the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR
 * CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */

package com.qq.tars.net.core.nio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Map;

import com.qq.tars.net.client.ticket.Ticket;
import com.qq.tars.net.client.ticket.TicketManager;
import com.qq.tars.net.core.Request;
import com.qq.tars.net.core.Response;

public final class WorkThread implements Runnable {

    private Request req;

    private Response resp;

    private SelectorManager selectorManager = null;

    public Request getRequest() {
        return req;
    }

    WorkThread(Response resp, SelectorManager selectorManager) {
        this.resp = resp;
        udpSession = null;
    }

    WorkThread(Request req, SelectorManager selectorManager) {
        this.req = req;
        udpSession = null;
        this.selectorManager = selectorManager;
    }

    private final UDPSession udpSession;

    private ByteBuffer udpBuffer;

    WorkThread(UDPSession session, ByteBuffer buffer, SelectorManager selectorManager) {
        this.udpSession = session;
        this.udpBuffer = buffer;
        this.selectorManager = selectorManager;
    }

    private void parseDatagramPacket() throws IOException {
        Object obj = this.udpSession.parseDatagramPacket(udpBuffer);
        if (obj instanceof Request) {
            this.req = (Request) obj;
        } else if (obj instanceof Response) {
            this.resp = (Response) obj;
        }
    }

    public void run() {
        try {
            if (udpSession != null) {
                parseDatagramPacket();
                udpBuffer = null;
            }

            if (req != null) {
                //读请求
                req.setProcessTime(System.currentTimeMillis());
                //解析数据流
                req.init();
                //这里拿到processor处理request
                Response res = selectorManager.getProcessor().process(req, req.getIoSession());
                //非异步模式，TCPSession写入response
                if (!res.isAsyncMode()) req.getIoSession().write(res);
            } else if (resp != null) {
                //输出
                resp.init();
                Ticket<Response> ticket = TicketManager.getTicket(resp.getTicketNumber());
                if (ticket == null) {
                    String s = "failed to fetch request for this response. [from:" + resp.getSession().getRemoteIp() + ":" + resp.getSession().getRemotePort() + "]";
                    System.out.println(s);
                    return;
                }
                fillDitributedContext(ticket.request().getDistributedContext());
                //通知response
                ticket.notifyResponse(resp);
                //更新计数器
                ticket.countDown();
                //移除该ticket
                TicketManager.removeTicket(ticket.getTicketNumber());
            }
        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            clearDistributedContext();
        }
    }

    private void fillDitributedContext(Map<String, String> data) {
    }

    private void clearDistributedContext() {
    }
}
