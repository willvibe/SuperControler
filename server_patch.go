// 服务端代码补丁 - 需要添加到现有的 server/main.go 中
// 这些代码实现了设备列表查询和推送功能

package main

import (
	"encoding/json"
	"log"
)

// 需要添加的新消息类型
const (
	MsgGetDevices  MsgType = "get_devices"   // 主控端请求设备列表
	MsgDevicesList MsgType = "devices_list"  // 服务端返回设备列表
	MsgDeviceOnline  MsgType = "device_online"   // 设备上线通知
	MsgDeviceOffline MsgType = "device_offline"  // 设备下线通知
)

// 设备信息结构
type DeviceInfo struct {
	DeviceID  string `json:"device_id"`
	Name      string `json:"name"`
	PublicIP  string `json:"public_ip"`
	Online    bool   `json:"online"`
}

// 在 dispatch 函数的 switch 中添加以下 case：

/*
case MsgGetDevices:
    // 主控端请求在线设备列表
    var deviceList []DeviceInfo
    s.mu.RLock()
    for id, client := range s.clients {
        if client.deviceID != "" && client.deviceID != from.deviceID {
            deviceList = append(deviceList, DeviceInfo{
                DeviceID: client.deviceID,
                Name:     client.deviceID, // 或使用 client.name 如果有存储
                PublicIP: client.publicIP,
                Online:   true,
            })
        }
    }
    s.mu.RUnlock()

    payload, _ := json.Marshal(map[string]interface{}{
        "devices": deviceList,
    })
    from.Send(Envelope{Type: MsgDevicesList, Payload: payload})
    log.Printf("[devices_list] sent %d devices to %s", len(deviceList), from.deviceID)
*/

// 在 register 成功后，广播设备上线通知给其他客户端：

/*
// 在 MsgRegister case 中，注册成功后添加：
// 广播设备上线通知
s.mu.RLock()
for _, client := range s.clients {
    if client.deviceID != "" && client.deviceID != from.deviceID {
        payload, _ := json.Marshal(map[string]interface{}{
            "device_id": from.deviceID,
            "name":      from.deviceID,
            "public_ip": from.publicIP,
        })
        client.Send(Envelope{Type: MsgDeviceOnline, Payload: payload})
    }
}
s.mu.RUnlock()
*/

// 在 removeClient 中，广播设备下线通知：

/*
// 在 removeClient 函数中，删除客户端后添加：
// 广播设备下线通知
s.mu.RLock()
for _, client := range s.clients {
    if client.deviceID != "" && client.deviceID != c.deviceID {
        payload, _ := json.Marshal(map[string]interface{}{
            "device_id": c.deviceID,
            "name":      c.deviceID,
            "public_ip": c.publicIP,
        })
        client.Send(Envelope{Type: MsgDeviceOffline, Payload: payload})
    }
}
s.mu.RUnlock()
*/

// ========== 完整修改后的 dispatch 函数 ==========

/*
func (s *Server) dispatch(from *Client, env *Envelope) {
    switch env.Type {

    case MsgRegister:
        var p RegisterPayload
        if json.Unmarshal(env.Payload, &p) != nil || p.DeviceID == "" {
            return
        }
        from.deviceID = p.DeviceID
        from.pubKey   = p.PublicKey
        from.udpPort  = p.UDPPort

        s.mu.Lock()
        s.clients[p.DeviceID] = from
        s.mu.Unlock()

        log.Printf("[register] id=%s ip=%s udp=%d", p.DeviceID, from.publicIP, p.UDPPort)

        payload, _ := json.Marshal(map[string]string{"public_ip": from.publicIP})
        from.Send(Envelope{Type: MsgRegistered, Payload: payload})

        // 广播设备上线通知
        s.broadcastDeviceStatus(from, true)

    case MsgConnect:
        var p ConnectPayload
        if json.Unmarshal(env.Payload, &p) != nil || p.TargetID == "" {
            return
        }
        from.pubKey  = p.PublicKey
        from.udpPort = p.UDPPort

        s.mu.RLock()
        target, ok := s.clients[p.TargetID]
        s.mu.RUnlock()

        if !ok {
            errPayload, _ := json.Marshal(map[string]string{"message": "device_not_found"})
            from.Send(Envelope{Type: MsgError, Payload: errPayload})
            return
        }

        from.peerID   = p.TargetID
        target.peerID = from.deviceID

        sessionKey := newSessionKey()

        toCtrl, _ := json.Marshal(PunchInfoPayload{
            PeerIP:     target.publicIP,
            PeerPort:   target.udpPort,
            PeerPubKey: target.pubKey,
            Role:       "controller",
            SessionKey: sessionKey,
        })
        toDevice, _ := json.Marshal(PunchInfoPayload{
            PeerIP:     from.publicIP,
            PeerPort:   from.udpPort,
            PeerPubKey: from.pubKey,
            Role:       "controlled",
            SessionKey: sessionKey,
        })

        from.Send(Envelope{Type: MsgPunchInfo, Payload: toCtrl})
        target.Send(Envelope{Type: MsgPunchInfo, Payload: toDevice})
        log.Printf("[connect] %s → %s session=%s", from.deviceID, p.TargetID, sessionKey)

    case MsgGetDevices:
        // 主控端请求在线设备列表
        var deviceList []DeviceInfo
        s.mu.RLock()
        for id, client := range s.clients {
            if client.deviceID != "" && client.deviceID != from.deviceID {
                deviceList = append(deviceList, DeviceInfo{
                    DeviceID: client.deviceID,
                    Name:     client.deviceID,
                    PublicIP: client.publicIP,
                    Online:   true,
                })
            }
        }
        s.mu.RUnlock()

        payload, _ := json.Marshal(map[string]interface{}{
            "devices": deviceList,
        })
        from.Send(Envelope{Type: MsgDevicesList, Payload: payload})
        log.Printf("[devices_list] sent %d devices to %s", len(deviceList), from.deviceID)

    case MsgP2PFail:
        log.Printf("[relay mode] %s ↔ %s", from.deviceID, from.peerID)

    case MsgRelay:
        if from.peerID == "" {
            return
        }
        s.mu.RLock()
        peer, ok := s.clients[from.peerID]
        s.mu.RUnlock()
        if ok {
            env.From = from.deviceID
            peer.Send(*env)
        }
    }
}

func (s *Server) broadcastDeviceStatus(device *Client, online bool) {
    msgType := MsgDeviceOffline
    if online {
        msgType = MsgDeviceOnline
    }
    payload, _ := json.Marshal(map[string]interface{}{
        "device_id": device.deviceID,
        "name":      device.deviceID,
        "public_ip": device.publicIP,
    })

    s.mu.RLock()
    clients := make([]*Client, 0, len(s.clients))
    for _, c := range s.clients {
        if c.deviceID != "" && c.deviceID != device.deviceID {
            clients = append(clients, c)
        }
    }
    s.mu.RUnlock()

    for _, client := range clients {
        client.Send(Envelope{Type: msgType, Payload: payload})
    }
}

func (s *Server) removeClient(c *Client) {
    s.mu.Lock()
    if c.deviceID != "" {
        delete(s.clients, c.deviceID)
    }
    s.mu.Unlock()

    // 广播设备下线通知
    s.broadcastDeviceStatus(c, false)

    c.mu.Lock()
    if !c.closed {
        c.closed = true
        close(c.send)
    }
    c.mu.Unlock()

    log.Printf("[disconnect] id=%s", c.deviceID)
}
*/
