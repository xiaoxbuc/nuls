package io.nuls.network.service.impl.netty;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.socket.SocketChannel;
import io.netty.util.Attribute;
import io.netty.util.AttributeKey;
import io.nuls.core.context.NulsContext;
import io.nuls.core.utils.log.Log;
import io.nuls.core.utils.network.IpUtil;
import io.nuls.core.utils.spring.lite.annotation.Autowired;
import io.nuls.network.entity.Node;
import io.nuls.network.service.NetworkService;

import java.io.UnsupportedEncodingException;
import java.nio.ByteBuffer;
import java.util.Map;

public class ClientChannelHandler extends ChannelInboundHandlerAdapter {

    @Autowired
    private NetworkService networkService;

    private AttributeKey<Node> key = AttributeKey.valueOf("node");

    public ClientChannelHandler() {

    }

    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        SocketChannel channel = (SocketChannel) ctx.channel();
        Attribute<Node> nodeAttribute = channel.attr(key);
        Node node = nodeAttribute.get();

        String nodeId = node == null ? null : node.getId();
        System.out.println("---------------------- client channelRegistered -----------" + nodeId);

        Map<String, Node> nodes = getNetworkService().getNodes();
        // If a node with the same IP already in nodes, as a out node, can not add anymore
        for (Node n : nodes.values()) {
            //System.out.println(n.toString());
            //both ip and port equals , it means the node is myself
            if (n.getIp().equals(node.getIp()) && n.getPort() != node.getSeverPort()) {
                System.out.println("----------------------client: it already had a connection: " + n.getId() + " type:" + n.getType() + ", this connection: " + nodeId + "---------------------- ");
                ctx.channel().close();
                return;
            }
        }
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        String channelId = ctx.channel().id().asLongText();
        SocketChannel channel = (SocketChannel) ctx.channel();
        String nodeId = IpUtil.getNodeId(channel.remoteAddress());
        System.out.println(" ---------------------- client channelActive ----------" + nodeId);

        Attribute<Node> nodeAttribute = channel.attr(key);
        Node node = nodeAttribute.get();

        try {
            NioChannelMap.add(channelId, channel);
            node.setChannelId(channelId);
            node.setStatus(Node.CONNECT);
            getNetworkService().addConnNode(node);
        } catch (Exception e) {
            System.out.println(nodeId);
            e.printStackTrace();
        }

    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        SocketChannel channel = (SocketChannel) ctx.channel();
        String nodeId = IpUtil.getNodeId(channel.remoteAddress());
        System.out.println(" ---------------------- client channelInactive ---------------------- " + nodeId);

        String channelId = ctx.channel().id().asLongText();
        NioChannelMap.remove(channelId);
        Node node = getNetworkService().getNode(nodeId);
        if (node != null) {
            if (node.getChannelId() == null || channelId.equals(node.getChannelId())) {
                getNetworkService().removeNode(node.getId());
            } else {
                System.out.println("---------------- client channelId different----------------" + channelId + "," + node.getChannelId());
            }
        }
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws UnsupportedEncodingException {
        SocketChannel channel = (SocketChannel) ctx.channel();
        String nodeId = IpUtil.getNodeId(channel.remoteAddress());
        Node node = getNetworkService().getNode(nodeId);
        if (node != null && node.isAlive()) {
            ByteBuf buf = (ByteBuf) msg;
            byte[] bytes = new byte[buf.readableBytes()];
            buf.readBytes(bytes);
            buf.release();
            ByteBuffer buffer = ByteBuffer.allocate(bytes.length);
            buffer.put(bytes);
            getNetworkService().receiveMessage(buffer, node);
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        System.out.println("--------------- ClientChannelHandler exceptionCaught :" + cause.getMessage());
        Log.debug("--------------- ClientChannelHandler exceptionCaught :" + cause.getMessage(), cause);
        ctx.channel().close();
    }


    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        SocketChannel channel = (SocketChannel) ctx.channel();
        Attribute<Node> nodeAttribute = channel.attr(key);
        Node node = nodeAttribute.get();
        if (!channel.isActive() && node != null) {
            getNetworkService().deleteNode(node.getId());
        }
    }

    private NetworkService getNetworkService() {
        if (networkService == null) {
            networkService = NulsContext.getServiceBean(NetworkService.class);
        }
        return networkService;
    }


}
