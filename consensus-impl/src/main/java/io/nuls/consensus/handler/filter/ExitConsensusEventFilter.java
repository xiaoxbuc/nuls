package io.nuls.consensus.handler.filter;

import io.nuls.consensus.event.ExitConsensusEvent;
import io.nuls.consensus.event.JoinConsensusEvent;
import io.nuls.event.bus.event.filter.NulsEventFilter;
import io.nuls.event.bus.event.filter.NulsEventFilterChain;

/**
 * @author Niels
 * @date 2017/12/6
 */
public class ExitConsensusEventFilter implements NulsEventFilter<ExitConsensusEvent> {
    @Override
    public void doFilter(ExitConsensusEvent event, NulsEventFilterChain chain) {
        //todo


        chain.doFilter(event);
    }
}
