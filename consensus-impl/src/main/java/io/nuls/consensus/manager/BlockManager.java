/*
 *
 * MIT License
 *
 * Copyright (c) 2017-2018 nuls.io
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 *
 */

package io.nuls.consensus.manager;

import io.nuls.consensus.cache.manager.block.ConfirmingBlockCacheManager;
import io.nuls.consensus.cache.manager.block.BlockCacheBuffer;
import io.nuls.consensus.cache.manager.tx.ConfirmingTxCacheManager;
import io.nuls.consensus.cache.manager.tx.OrphanTxCacheManager;
import io.nuls.consensus.cache.manager.tx.ReceivedTxCacheManager;
import io.nuls.consensus.entity.GetBlockParam;
import io.nuls.consensus.entity.block.BifurcateProcessor;
import io.nuls.consensus.entity.block.BlockHeaderChain;
import io.nuls.consensus.entity.block.BlockRoundData;
import io.nuls.consensus.entity.block.HeaderDigest;
import io.nuls.consensus.event.GetBlockHeaderEvent;
import io.nuls.consensus.event.GetBlockRequest;
import io.nuls.consensus.service.intf.BlockService;
import io.nuls.consensus.utils.DownloadDataUtils;
import io.nuls.core.chain.entity.Block;
import io.nuls.core.chain.entity.BlockHeader;
import io.nuls.core.chain.entity.NulsDigestData;
import io.nuls.core.chain.entity.Transaction;
import io.nuls.core.constant.ErrorCode;
import io.nuls.core.constant.TxStatusEnum;
import io.nuls.core.context.NulsContext;
import io.nuls.core.exception.NulsException;
import io.nuls.core.exception.NulsRuntimeException;
import io.nuls.core.utils.log.BlockLog;
import io.nuls.core.utils.log.Log;
import io.nuls.core.validate.ValidateResult;
import io.nuls.event.bus.service.intf.EventBroadcaster;
import io.nuls.ledger.service.intf.LedgerService;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * @author Niels
 * @date 2018/3/26
 */
public class BlockManager {

    private static final BlockManager INSTANCE = new BlockManager();
    private NulsContext context = NulsContext.getInstance();

    private ConfirmingBlockCacheManager confirmingBlockCacheManager = ConfirmingBlockCacheManager.getInstance();
    private BlockCacheBuffer blockCacheBuffer = BlockCacheBuffer.getInstance();

    private EventBroadcaster eventBroadcaster;
    private LedgerService ledgerService;

    private BifurcateProcessor bifurcateProcessor = BifurcateProcessor.getInstance();
    private ConfirmingTxCacheManager confirmingTxCacheManager = ConfirmingTxCacheManager.getInstance();
    private ReceivedTxCacheManager txCacheManager = ReceivedTxCacheManager.getInstance();
    private OrphanTxCacheManager orphanTxCacheManager = OrphanTxCacheManager.getInstance();

    private long storedHeight;
    private String lastAppravedHash;

    private BlockManager() {
    }

    public static BlockManager getInstance() {
        return INSTANCE;
    }

    public void init() {
        eventBroadcaster = NulsContext.getServiceBean(EventBroadcaster.class);
        ledgerService = NulsContext.getServiceBean(LedgerService.class);
    }

    public synchronized void addBlock(Block block, boolean verify, String nodeId) {
        if (block == null || block.getHeader() == null || block.getTxs() == null || block.getTxs().isEmpty()) {
            BlockLog.warn("the block data error============================");
            return;
        }

        BlockRoundData roundData = new BlockRoundData(block.getHeader().getExtend());
        Log.debug("cache block:" + block.getHeader().getHash() +
                ",\nheight(" + block.getHeader().getHeight() + "),round(" + roundData.getRoundIndex() + "),index(" + roundData.getPackingIndexOfRound() + "),roundStart:" + roundData.getRoundStartTime());
        if (storedHeight == 0) {
            BlockService blockService = NulsContext.getServiceBean(BlockService.class);
            if (null != blockService) {
                storedHeight = blockService.getLocalSavedHeight();
            }
        }
        if (block.getHeader().getHeight() <= storedHeight) {
            Log.info("discard block height:" + block.getHeader().getHeight() + ", address:" + block.getHeader().getPackingAddress() + ",from:" + nodeId);
            return;
        }
        if (verify) {
            ValidateResult result = block.verify();
            if (result.isFailed() && result.getErrorCode() != ErrorCode.ORPHAN_BLOCK && result.getErrorCode() != ErrorCode.ORPHAN_TX) {
                Log.debug("discard a block :" + result.getMessage());
                return;
            } else if (result.isFailed()) {
                blockCacheBuffer.cacheBlock(block);
                return;
            }
        }
        boolean success = confirmingBlockCacheManager.cacheBlock(block);
        if (!success) {
            blockCacheBuffer.cacheBlock(block);
            BlockLog.info("orphan cache block height:" + block.getHeader().getHeight() + ", preHash:" + block.getHeader().getPreHash() + " , hash:" + block.getHeader().getHash() + ", address:" + block.getHeader().getPackingAddress());
            boolean hasPre = blockCacheBuffer.getBlock(block.getHeader().getPreHash().getDigestHex()) != null;
            if (!hasPre) {
                GetBlockRequest request = new GetBlockRequest();
                GetBlockParam params = new GetBlockParam();
                params.setToHash(block.getHeader().getPreHash());
                request.setEventBody(params);
                this.eventBroadcaster.broadcastAndCacheAysn(request,false);
            }
            return;
        }
        boolean needUpdateBestBlock = bifurcateProcessor.addHeader(block.getHeader());
        if (needUpdateBestBlock) {
            context.setBestBlock(block);
        }
        if (bifurcateProcessor.getChainSize() == 1) {
            try {
                this.appravalBlock(block);
                this.lastAppravedHash = block.getHeader().getHash().getDigestHex();
                checkNextblock(block.getHeader().getHash().getDigestHex());
            } catch (Exception e) {
                Log.error(e);
                confirmingBlockCacheManager.removeBlock(block.getHeader().getHash().getDigestHex());
                blockCacheBuffer.cacheBlock(block);
                return;
            }
        } else {
            Block lastAppravedBlock = confirmingBlockCacheManager.getBlock(lastAppravedHash);
            if (null != lastAppravedBlock) {
                this.rollbackAppraval(lastAppravedBlock);
            }
        }
            Set<String> keySet = blockCacheBuffer.getHeaderCacheMap().keySet();
            for (String key : keySet) {
                BlockHeader header = blockCacheBuffer.getBlockHeader(key);
                if (header == null) {
                    continue;
                }
                if (header.getPreHash().getDigestHex().equals(block.getHeader().getHash())) {
                    Block nextBlock = blockCacheBuffer.getBlock(key);
                    this.addBlock(nextBlock, true, null);
                }
            }
    }

    private void appravalBlock(Block block) {
        for (int i = 0; i < block.getHeader().getTxCount(); i++) {
            Transaction tx = block.getTxs().get(i);
            tx.setBlockHeight(block.getHeader().getHeight());
            tx.setIndex(i);
            tx.setIndex(i);
            if (tx.getStatus() == null || tx.getStatus() == TxStatusEnum.CACHED) {
                try {
                    tx.verifyWithException();
                    this.ledgerService.approvalTx(tx);
                    confirmingTxCacheManager.putTx(tx);
                } catch (NulsException e) {
                    rollbackTxList(block.getTxs(), 0, i);
                    Log.error(e);
                    throw new NulsRuntimeException(e);
                }
            }
        }
        txCacheManager.removeTx(block.getTxHashList());
        orphanTxCacheManager.removeTx(block.getTxHashList());
    }

    private void rollbackTxList(List<Transaction> txList, int start, int end) {
        List<NulsDigestData> txHashList = new ArrayList<>();
        for (int i = start; i <= end && i < txList.size(); i++) {
            Transaction tx = txList.get(i);
            if (tx.getStatus() == TxStatusEnum.AGREED) {
                try {
                    ledgerService.rollbackTx(tx);

                } catch (NulsException e) {
                    Log.error(e);
                }
                txHashList.add(tx.getHash());
            }
        }
        confirmingTxCacheManager.removeTxList(txHashList);

    }

    private void rollbackAppraval(Block block) {
        if (null == block) {
            Log.warn("the block is null!");
            return;
        }
        this.rollbackTxList(block.getTxs(), 0, block.getTxs().size());
        this.lastAppravedHash = block.getHeader().getPreHash().getDigestHex();
        Block preBlock = this.getBlock(lastAppravedHash);
        List<String> hashList = this.bifurcateProcessor.getAllHashList(block.getHeader().getHeight() - 1);
        if (hashList.size() > 1) {
            this.rollbackAppraval(preBlock);
        }
    }

    public Block getBlock(long height) {
        String hash = this.bifurcateProcessor.getBlockHash(height);
        if (hash != null) {
            return getBlock(hash);
        }
        return null;
    }

    public Block getBlock(String hash) {
        Block block = confirmingBlockCacheManager.getBlock(hash);
        if (block == null) {
            block = this.blockCacheBuffer.getBlock(hash);
        }
        return block;
    }

    public void rollback(Block block) {
        this.rollbackAppraval(block);
    }


    private void checkNextblock(String hash) {
        Set<String> nextHashSet = blockCacheBuffer.getNextHash(hash);
        if (null == nextHashSet || nextHashSet.isEmpty()) {
            return;
        }
        for (String nextHash : nextHashSet) {
            Block block = blockCacheBuffer.getBlock(nextHash);
            if (null == block) {
                return;
            }
            blockCacheBuffer.removeBlock(nextHash);
            this.addBlock(block, true, null);
        }
    }

    public long getStoredHeight() {
        return storedHeight;
    }

    public boolean processingBifurcation(long height) {
        return this.bifurcateProcessor.processing(height);
    }

    public void setStoredHeight(long storedHeight) {
        this.storedHeight = storedHeight;
    }

    public void removeBlock(String hash) {
        this.bifurcateProcessor.removeHash(hash);
        confirmingBlockCacheManager.removeBlock(hash);
        blockCacheBuffer.removeBlock(hash);
    }

    public BlockHeader getBlockHeader(String hashHex) {
        BlockHeader header = confirmingBlockCacheManager.getBlockHeader(hashHex);
        if (null == header) {
            header = blockCacheBuffer.getBlockHeader(hashHex);
        }
        return header;
    }

    public BlockHeader getBlockHeader(long height) {
        String hash = this.bifurcateProcessor.getBlockHash(height);
        if (hash == null) {
            return null;
        }
        return confirmingBlockCacheManager.getBlockHeader(hash);
    }

    public void clear() {
        this.bifurcateProcessor.clear();
        this.confirmingBlockCacheManager.clear();
        this.blockCacheBuffer.clear();

    }

    public Block getHighestBlock() {
        BlockHeaderChain chain = bifurcateProcessor.getLongestChain();
        if (null == chain) {
            return null;
        }
        HeaderDigest headerDigest = chain.getHeaderDigestList().get(chain.getHeaderDigestList().size() - 1);
        return this.getBlock(headerDigest.getHash());
    }
}
