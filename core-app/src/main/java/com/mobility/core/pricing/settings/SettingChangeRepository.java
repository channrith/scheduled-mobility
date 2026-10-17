package com.mobility.core.pricing.settings;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

interface SettingChangeRepository extends JpaRepository<SettingChange, UUID> {

	Optional<SettingChange> findFirstBySettingOrderBySeqDesc(Setting setting);

	List<SettingChange> findTop50BySettingOrderBySeqDesc(Setting setting);
}
