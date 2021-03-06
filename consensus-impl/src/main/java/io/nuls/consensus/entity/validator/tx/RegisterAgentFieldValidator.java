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

package io.nuls.consensus.entity.validator.tx;

import io.nuls.account.entity.Address;
import io.nuls.consensus.constant.PocConsensusConstant;
import io.nuls.consensus.entity.Consensus;
import io.nuls.consensus.entity.ConsensusAgentImpl;
import io.nuls.consensus.entity.member.Agent;
import io.nuls.consensus.entity.tx.RegisterAgentTransaction;
import io.nuls.core.utils.str.StringUtils;
import io.nuls.core.validate.NulsDataValidator;
import io.nuls.core.validate.ValidateResult;

/**
 * @author Niels
 * @date 2018/3/25
 */
public class RegisterAgentFieldValidator implements NulsDataValidator<RegisterAgentTransaction> {
    @Override
    public ValidateResult validate(RegisterAgentTransaction tx) {
        Consensus<Agent> agent = tx.getTxData();
        if (null == agent) {
            return ValidateResult.getFailedResult("tx data can not be null!");
        }
        if (!Address.validAddress(agent.getAddress()) || !Address.validAddress(agent.getExtend().getPackingAddress())) {
            return ValidateResult.getFailedResult("Address format wrong!");
        }
        if (agent.getAddress().equals(agent.getExtend().getPackingAddress())) {
            return ValidateResult.getFailedResult("It's not safe:agentAddress equals packingAddress");
        }

        if (agent.getExtend().getStartTime() <= 0) {
            return ValidateResult.getFailedResult("start time cannot be 0!");
        }
        return ValidateResult.getSuccessResult();
    }
}
