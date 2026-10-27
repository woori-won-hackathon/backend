package com.example.wonway.policy.ranking;

import com.example.wonway.common.contract.ChannelType;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.util.List;
import java.util.Set;

@Component
public class DefaultChannelCapabilityProvider implements ChannelCapabilityProvider {
    private final boolean demo;
    public DefaultChannelCapabilityProvider(@Value("${policy.channel-demo-mode:false}") boolean demo) {this.demo=demo;}

    @Override public List<Capability> snapshot(String organization) {
        String contactUrl=switch(organization) {
            case "WOORI_BANK" -> "https://spot.wooribank.com/pot/Dream?withyou=CQCCS0004";
            case "WOORI_CARD" -> "https://pc.wooricard.com/dcpc/yh1/cct/cct04/H1CCT204S06.do";
            default -> null; // 지점 연락처를 대표번호로 임의 대체하지 않는다.
        };
        return List.of(
            new Capability(ChannelType.CHATBOT,Availability.AVAILABLE,Set.of("GENERAL_GUIDANCE","SELF_SERVICE_GUIDANCE"),true,false,
                null,"MVP_POLICY_GUIDANCE_ONLY",false),
            new Capability(ChannelType.PHONE,demo && contactUrl!=null?Availability.AVAILABLE:Availability.UNKNOWN,
                Set.of("GENERAL_GUIDANCE","SELF_SERVICE_GUIDANCE","ACCOUNT_SPECIFIC_CHECK"),demo,true,
                contactUrl,demo?"DEMO_CAPABILITY_NOT_LIVE":"LIVE_SUPPORT_AND_AVAILABILITY_UNVERIFIED",demo),
            new Capability(ChannelType.EMAIL,demo && organization.equals("WOORI_BANK")?Availability.AVAILABLE:Availability.UNKNOWN,
                Set.of("GENERAL_GUIDANCE","SELF_SERVICE_GUIDANCE"),demo,false,
                organization.equals("WOORI_BANK")?"https://spot.wooribank.com/pot/Dream?withyou=CQCCS0009":null,
                demo?"DEMO_CAPABILITY_NOT_LIVE":"EMAIL_SUPPORT_SCOPE_UNVERIFIED",demo)
        );
    }
}
