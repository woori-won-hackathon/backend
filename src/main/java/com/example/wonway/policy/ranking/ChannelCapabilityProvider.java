package com.example.wonway.policy.ranking;

import com.example.wonway.common.contract.ChannelType;
import java.util.List;
import java.util.Set;

/** 운영 상태·지원 범위는 서버 제공값. LLM 도구의 입력 인자로 받지 않는다. */
public interface ChannelCapabilityProvider {
    enum Availability { AVAILABLE, UNAVAILABLE, UNKNOWN }
    record Capability(ChannelType channel,Availability availability,Set<String> supportedGoals,
                      boolean supportVerified,boolean identityNeededForIndividualCheck,
                      String officialUrl,String basis,boolean demo) {}
    List<Capability> snapshot(String contactOrganization);
}
